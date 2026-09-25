package studio.bookhtml.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.DurableJson;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Bounded, private, derived evidence for an explicitly retried handwritten page.
 * Never stores credentials/images or authorizes a model call; never publishes a Page.
 * One record per page, six validated regions, with compare-and-replace writer ownership.
 */
@Service
public final class ManuscriptResumeStore {
    static final int MAX_REGIONS=6, MAX_RESPONSE_CHARS=24576, MAX_BYTES=1024*1024, MAX_PAGE_RECORDS=64;
    static final Duration RETENTION=Duration.ofDays(7);
    private static final Object[] LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private final BookStore store;
    private final ObjectMapper json;
    private final Clock clock;
    public record Region(int index,String inputHash,String response) {}
    public record Snapshot(int schemaVersion,String bookId,int pageNumber,String sourceHash,String contractHash,
                           UUID ownerId,int baseRevision,Instant startedAt,Instant updatedAt,
                           String completeSourcesHash,List<Region> regions,String integrityHash) {}
    @org.springframework.beans.factory.annotation.Autowired
    public ManuscriptResumeStore(BookStore store,ObjectMapper json) {this(store,json,Clock.systemUTC());}
    ManuscriptResumeStore(BookStore store,ObjectMapper json,Clock clock) {
        this.store=Objects.requireNonNull(store);this.clock=Objects.requireNonNull(clock);
        this.json=Objects.requireNonNull(json).copy().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                // Numeric Instant values pass through JsonNode; IEEE-754 conversion can
                // lose Linux clock nanoseconds and invalidate our own checksum on read.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }
    Path path(String book,int page) {
        if(page<1)throw new IllegalArgumentException("invalid manuscript page");
        return store.bookDir(book).resolve("manuscript-resume").resolve(page+".json");
    }
    private static Object lock(Path path) {return LOCKS[Math.floorMod(path.getParent().toAbsolutePath().normalize().hashCode(),LOCKS.length)];}
    public Session open(String book,int page,String sourceHash,String contractHash) {
        Path path=path(book,page);
        synchronized(lock(path)) {
            try {
                if(!digest(sourceHash)||!digest(contractHash))throw new IOException("invalid manuscript identity");
                Page current=store.readPage(book,page);
                if(current==null || current.pageNumber()!=page)throw new IOException("manuscript page missing");
                Snapshot previous=read(path,book,page);Instant now=clock.instant();
                boolean same=previous!=null && previous.sourceHash().equals(sourceHash) && previous.contractHash().equals(contractHash)
                        && !previous.startedAt().isAfter(now) && !previous.updatedAt().isAfter(now) && previous.startedAt().plus(RETENTION).isAfter(now);
                // A normal explicit reprocess of an already published complete transcription
                // must run afresh. A crash before publication can reuse all completed regions.
                boolean published=same && previous.completeSourcesHash()!=null
                        && BookStore.revisionOrZero(current)>previous.baseRevision() && current.lastCommitId()!=null
                        && previous.completeSourcesHash().equals(sourcesHash(current.sourceRecords()));
                boolean resume=same&&!published;
                Snapshot next=new Snapshot(1,book,page,sourceHash,contractHash,UUID.randomUUID(),
                        resume?previous.baseRevision():BookStore.revisionOrZero(current),resume?previous.startedAt():now,now,
                        resume?previous.completeSourcesHash():null,resume?previous.regions():List.of(),null);
                if(previous==null)makeRoom(path,book,now);
                next=signed(next);save(path,next);return new Session(path,next,true);
            } catch(Exception unavailable) {
                // A damaged optional file is left intact; do not turn it into authoritative
                // text or silently overwrite it. The explicit task can still transcribe normally.
                return new Session(path,null,false);
            }
        }
    }
    /** Capacity eviction touches only verified expired or already-published complete evidence.
     * Unfinished recent evidence is never evicted to make a new cloud task look resumable. */
    private void makeRoom(Path target,String book,Instant now)throws IOException {
        Path directory=target.getParent();DurableJson.rejectLinks(directory);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        List<Path> candidates;
        try(var paths=Files.list(directory)){candidates=paths.sorted().limit(MAX_PAGE_RECORDS+1L).toList();}
        if(candidates.size()<MAX_PAGE_RECORDS)return;
        int checked=0;
        for(Path candidate:candidates) {
            if(checked++>=4)break;
            String name=candidate.getFileName().toString();if(!name.matches("[1-9][0-9]{0,8}\\.json"))continue;
            try {
                int page=Integer.parseInt(name.substring(0,name.length()-5));Snapshot old=read(candidate,book,page);
                if(old==null)continue;
                boolean expired=!old.startedAt().plus(RETENTION).isAfter(now);
                if(!expired&&old.completeSourcesHash()!=null) {
                    Page published=store.readPage(book,page);
                    expired=published!=null && published.lastCommitId()!=null && BookStore.revisionOrZero(published)>old.baseRevision()
                            && old.completeSourcesHash().equals(sourcesHash(published.sourceRecords()));
                }
                if(expired){Files.delete(candidate);return;}
            } catch(Exception unsafeOrUnreadable){/* Leave invalid or unresolved evidence untouched. */}
        }
        throw new IOException("manuscript resume capacity unavailable");
    }
    private void save(Path path,Snapshot snapshot) throws IOException {
        DurableJson.rejectLinks(path);
        DurableJson.write(path,snapshot,json,MAX_BYTES);
    }
    Snapshot read(Path path,String book,int page) throws IOException {
        DurableJson.rejectLinks(path);
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsafe manuscript cache");
        byte[] bytes;
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(MAX_BYTES+1);}
        if(bytes.length>MAX_BYTES)throw new IOException("manuscript cache too large");
        Snapshot value;
        try(var parser=json.getFactory().createParser(bytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree=json.readTree(parser);
            if(tree==null||!tree.isObject()||parser.nextToken()!=null)throw new IOException("invalid manuscript cache");
            integer(tree,"schemaVersion",1,1);integer(tree,"pageNumber",1,Integer.MAX_VALUE);integer(tree,"baseRevision",0,Integer.MAX_VALUE);
            if(!tree.path("regions").isArray() || tree.path("regions").size()>MAX_REGIONS)throw new IOException("invalid manuscript regions");
            for(JsonNode region:tree.path("regions"))integer(region,"index",0,MAX_REGIONS-1);
            value=json.treeToValue(tree,Snapshot.class);
        } catch(RuntimeException invalid) {throw new IOException("invalid manuscript cache");}
        if(value==null || !book.equals(value.bookId()) || value.pageNumber()!=page || value.ownerId()==null
                || !digest(value.sourceHash()) || !digest(value.contractHash()) || !digest(value.integrityHash())
                || value.completeSourcesHash()!=null&&!digest(value.completeSourcesHash())
                || value.startedAt()==null || value.updatedAt()==null || value.updatedAt().isBefore(value.startedAt())
                || value.regions()==null || value.regions().size()>MAX_REGIONS)throw new IOException("manuscript identity mismatch");
        Set<Integer> indexes=new HashSet<>();
        for(Region region:value.regions()) {
            if(region==null || !indexes.add(region.index()) || !digest(region.inputHash()) || region.response()==null
                    || region.response().length()>MAX_RESPONSE_CHARS)throw new IOException("invalid manuscript region");
        }
        if(!checksum(value).equals(value.integrityHash()))throw new IOException("manuscript cache integrity mismatch");
        return value;
    }
    private static void integer(JsonNode tree,String key,int low,int high)throws IOException {
        JsonNode value=tree.path(key);
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<low||value.intValue()>high)throw new IOException("invalid manuscript integer");
    }
    private static boolean digest(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private String sourcesHash(List<Block> records)throws IOException {return sha(json.writeValueAsBytes(records==null?List.of():records));}
    private String checksum(Snapshot s)throws IOException {
        return sha(json.writeValueAsBytes(List.of(s.schemaVersion(),s.bookId(),s.pageNumber(),s.sourceHash(),s.contractHash(),
                s.ownerId(),s.baseRevision(),s.startedAt(),s.updatedAt(),s.completeSourcesHash()==null?"":s.completeSourcesHash(),s.regions())));
    }
    private Snapshot signed(Snapshot s)throws IOException {
        return new Snapshot(s.schemaVersion(),s.bookId(),s.pageNumber(),s.sourceHash(),s.contractHash(),s.ownerId(),s.baseRevision(),
                s.startedAt(),s.updatedAt(),s.completeSourcesHash(),s.regions(),checksum(s));
    }
    public final class Session {
        private final Path path;
        private Snapshot snapshot;
        private boolean writable,finished;
        private Session(Path path,Snapshot snapshot,boolean writable){this.path=path;this.snapshot=snapshot;this.writable=writable;}
        public synchronized boolean available(){return writable;}
        public synchronized String get(int index,String inputHash) {
            if(snapshot==null)return null;
            return snapshot.regions().stream().filter(r->r.index()==index && r.inputHash().equals(inputHash)).map(Region::response).findFirst().orElse(null);
        }
        public synchronized void discard(int index) {
            if(snapshot==null)return;
            update(snapshot.regions().stream().filter(r->r.index()!=index).toList(),null);
        }
        public synchronized void put(int index,String inputHash,String response) {
            if(snapshot==null||!writable||finished)return;
            if(index<0||index>=MAX_REGIONS||!digest(inputHash)||response==null||response.length()>MAX_RESPONSE_CHARS)
                throw new IllegalArgumentException("invalid manuscript region");
            List<Region> regions=new ArrayList<>(snapshot.regions().stream().filter(r->r.index()!=index).toList());
            regions.add(new Region(index,inputHash,response));regions.sort(Comparator.comparingInt(Region::index));
            update(List.copyOf(regions),null);
        }
        public synchronized void finish(List<Block> sources,boolean complete) {
            if(snapshot==null||finished)return;
            try {update(snapshot.regions(),complete?sourcesHash(sources):null);}
            catch(IOException unavailable){writable=false;}
            finally{finished=true;}
        }
        private void update(List<Region> regions,String completeHash) {
            if(!writable||finished)return;
            synchronized(lock(path)) {
                try {
                    Snapshot disk=read(path,snapshot.bookId(),snapshot.pageNumber());
                    if(disk==null||!disk.ownerId().equals(snapshot.ownerId())) {writable=false;return;}
                    Instant now=clock.instant();if(now.isBefore(snapshot.startedAt())){writable=false;return;}
                    Snapshot next=signed(new Snapshot(1,snapshot.bookId(),snapshot.pageNumber(),snapshot.sourceHash(),snapshot.contractHash(),
                            snapshot.ownerId(),snapshot.baseRevision(),snapshot.startedAt(),now,completeHash,regions,null));
                    save(path,next);snapshot=next;
                } catch(Exception unavailable) {writable=false;}
            }
        }
    }
}
