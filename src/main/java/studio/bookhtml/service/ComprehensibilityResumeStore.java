package studio.bookhtml.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.DurableJson;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Private, bounded derived review evidence. It cannot authorize a call or publish a Page.
 * The service revalidates every stored result against the exact current slices before use.
 */
@Service
public final class ComprehensibilityResumeStore {
    static final int MAX_GROUPS=32, MAX_RESULT_CHARS=49152, MAX_BYTES=4*1024*1024, MAX_PAGES=32;
    static final Duration RETENTION=Duration.ofDays(7);
    private static final Object[] LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    public record Group(int index,String inputHash,String result) {}
    public record Snapshot(int schemaVersion,String bookId,int pageNumber,String inputHash,int planned,
                           UUID ownerId,Instant startedAt,Instant updatedAt,List<Group> groups,String integrityHash) {}
    private final BookStore store;
    private final ObjectMapper json;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public ComprehensibilityResumeStore(BookStore store,ObjectMapper json){this(store,json,Clock.systemUTC());}
    ComprehensibilityResumeStore(BookStore store,ObjectMapper json,Clock clock){
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        this.json=Objects.requireNonNull(json).copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    Path path(String book,int page){
        if(page<1)throw new IllegalArgumentException("invalid review page");
        return store.bookDir(book).resolve("selfcheck-resume").resolve(page+".json");
    }
    private static Object lock(Path path){return LOCKS[Math.floorMod(path.getParent().toAbsolutePath().normalize().hashCode(),LOCKS.length)];}
    static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    public Session open(String book,int page,String inputHash,int planned){
        Path path=path(book,page);
        synchronized(lock(path)){
            try {
                if(!digest(inputHash)||planned<1||planned>MAX_GROUPS)throw new IOException("invalid review identity");
                var current=store.readPage(book,page);
                if(current==null||current.pageNumber()!=page)throw new IOException("review page missing");
                Snapshot previous=read(path,book,page);Instant now=clock.instant();
                boolean reuse=previous!=null&&previous.inputHash().equals(inputHash)&&previous.planned()==planned&&live(previous,now);
                if(previous==null)makeRoom(path,book,now);
                Snapshot next=signed(new Snapshot(1,book,page,inputHash,planned,UUID.randomUUID(),
                        reuse?previous.startedAt():now,now,reuse?previous.groups():List.of(),null));
                save(path,next);return new Session(path,next,true);
            } catch(Exception unavailable){
                // An optional damaged cache remains untouched. An already authorized check
                // may proceed without it; no automatic retry or new permission is created.
                return new Session(path,null,false);
            }
        }
    }
    private static boolean live(Snapshot s,Instant now){
        return !s.startedAt().isAfter(now)&&!s.updatedAt().isAfter(now)&&s.startedAt().plus(RETENTION).isAfter(now);
    }
    private void makeRoom(Path target,String book,Instant now)throws IOException{
        Path directory=target.getParent();DurableJson.rejectLinks(directory);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> candidates;
        try(var stream=Files.list(directory)){candidates=stream.limit(MAX_PAGES+1L).sorted().toList();}
        if(candidates.size()<MAX_PAGES)return;
        if(candidates.size()>MAX_PAGES)throw new IOException("review cache directory exceeds capacity");
        int inspected=0;
        for(Path candidate:candidates){
            if(inspected++>=4)break;
            String name=candidate.getFileName().toString();if(!name.matches("[1-9][0-9]{0,9}\\.json"))continue;
            try {
                int page=Integer.parseInt(name.substring(0,name.length()-5));Snapshot old=read(candidate,book,page);
                if(old!=null&&!old.startedAt().plus(RETENTION).isAfter(now)){
                    Files.delete(candidate);return;
                }
            }catch(Exception invalid){/* Never delete damaged or still-live partial evidence to make room. */}
        }
        throw new IOException("review cache capacity unavailable");
    }
    private void save(Path path,Snapshot value)throws IOException{DurableJson.rejectLinks(path);DurableJson.write(path,value,json,MAX_BYTES);}
    Snapshot read(Path path,String book,int page)throws IOException{
        DurableJson.rejectLinks(path);
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe review cache");
        byte[] bytes;
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IOException("review cache exceeds bound");
        Snapshot result;
        try(var parser=json.getFactory().createParser(bytes)){
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree=json.readTree(parser);
            if(tree==null||!tree.isObject()||parser.nextToken()!=null)throw new IOException("invalid review cache");
            integer(tree,"schemaVersion",1,1);integer(tree,"pageNumber",1,Integer.MAX_VALUE);integer(tree,"planned",1,MAX_GROUPS);
            if(!tree.path("groups").isArray()||tree.path("groups").size()>MAX_GROUPS)throw new IOException("invalid review groups");
            for(JsonNode group:tree.path("groups"))integer(group,"index",0,MAX_GROUPS-1);
            result=json.treeToValue(tree,Snapshot.class);
        }catch(RuntimeException invalid){throw new IOException("unreadable review cache");}
        if(result==null||!book.equals(result.bookId())||result.pageNumber()!=page||result.ownerId()==null
                ||!digest(result.inputHash())||!digest(result.integrityHash())||result.startedAt()==null||result.updatedAt()==null
                ||result.updatedAt().isBefore(result.startedAt())||result.groups()==null||result.groups().size()>result.planned())
            throw new IOException("review cache identity mismatch");
        Set<Integer> seen=new HashSet<>();
        for(Group group:result.groups()){
            if(group==null||!seen.add(group.index())||group.index()>=result.planned()||!digest(group.inputHash())
                    ||group.result()==null||group.result().length()>MAX_RESULT_CHARS)throw new IOException("invalid review group");
        }
        if(!checksum(result).equals(result.integrityHash()))throw new IOException("review cache integrity mismatch");
        return result;
    }
    private static void integer(JsonNode node,String key,int min,int max)throws IOException{
        JsonNode value=node.path(key);
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<min||value.intValue()>max)throw new IOException("invalid review integer");
    }
    private String checksum(Snapshot s)throws IOException{
        return studio.bookhtml.decision.DecisionHash.sha256Hex(json.writeValueAsString(List.of(s.schemaVersion(),s.bookId(),s.pageNumber(),s.inputHash(),
                s.planned(),s.ownerId(),s.startedAt(),s.updatedAt(),s.groups())));
    }
    private Snapshot signed(Snapshot s)throws IOException{
        return new Snapshot(s.schemaVersion(),s.bookId(),s.pageNumber(),s.inputHash(),s.planned(),s.ownerId(),s.startedAt(),s.updatedAt(),s.groups(),checksum(s));
    }
    public final class Session {
        private final Path path;
        private Snapshot snapshot;
        private boolean writable;
        private Session(Path path,Snapshot snapshot,boolean writable){this.path=path;this.snapshot=snapshot;this.writable=writable;}
        public synchronized boolean available(){return writable&&snapshot!=null&&live(snapshot,clock.instant());}
        public synchronized Instant expiresAt(){return snapshot==null?Instant.EPOCH:snapshot.startedAt().plus(RETENTION);}
        public synchronized String get(int index,String hash){
            if(snapshot==null||!live(snapshot,clock.instant()))return null;
            return snapshot.groups().stream().filter(g->g.index()==index&&g.inputHash().equals(hash)).map(Group::result).findFirst().orElse(null);
        }
        public synchronized void put(int index,String hash,String result){
            if(snapshot==null||!writable)return;
            if(index<0||index>=snapshot.planned()||!digest(hash)||result==null||result.length()>MAX_RESULT_CHARS)
                throw new IllegalArgumentException("invalid completed review group");
            List<Group> groups=new ArrayList<>(snapshot.groups().stream().filter(g->g.index()!=index).toList());
            groups.add(new Group(index,hash,result));groups.sort(Comparator.comparingInt(Group::index));update(List.copyOf(groups));
        }
        public synchronized void discard(int index){
            if(snapshot!=null)update(snapshot.groups().stream().filter(g->g.index()!=index).toList());
        }
        private void update(List<Group> groups){
            if(!writable||snapshot==null)return;
            synchronized(lock(path)){
                try {
                    Snapshot disk=read(path,snapshot.bookId(),snapshot.pageNumber());Instant now=clock.instant();
                    if(disk==null||!disk.ownerId().equals(snapshot.ownerId())||!live(snapshot,now)){writable=false;return;}
                    Snapshot next=signed(new Snapshot(1,snapshot.bookId(),snapshot.pageNumber(),snapshot.inputHash(),snapshot.planned(),
                            snapshot.ownerId(),snapshot.startedAt(),now,groups,null));
                    save(path,next);snapshot=next;
                }catch(Exception unavailable){writable=false;}
            }
        }
    }
}
