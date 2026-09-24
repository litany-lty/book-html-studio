package studio.bookhtml.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import studio.bookhtml.api.ApiException;
import org.springframework.http.HttpStatus;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.SourceChange;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Source operations retain one sequence across prepare and settlement. No guessed recovery. */
public final class SourceChangeJournal {
    private static final Object[] MONITORS = java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private record Key(Path directory,String book) {}
    private record Stamp(String name,long size,Object fileKey,Object modified,Object changed) {}
    private record Watermark(List<Stamp> stamp,long value) {}
    private static final Map<Key,Watermark> WATERMARKS = new LinkedHashMap<>(16,.75f,true);
    private static final long MAX_REPLAY_BYTES=64L*1024*1024;
    private final ObjectMapper json;
    private final DurableEventJournal wal=new DurableEventJournal();
    public SourceChangeJournal(ObjectMapper json) { this.json=Objects.requireNonNull(json); }
    public static Path eventsDir(Path dir) { return dir.resolve("source-events"); }
    /** Index publication must use the same monitor as every source admission. Never read Pages while holding it. */
    public static Object publicationMonitor(Path dir) {
        return MONITORS[Math.floorMod(dir.toAbsolutePath().normalize().hashCode(),MONITORS.length)];
    }
    private static Key key(Path dir,String book) { return new Key(dir.toAbsolutePath().normalize(),book); }
    private List<Path> segments(Path dir) throws IOException {
        Path events=eventsDir(dir); DurableJson.rejectLinks(events);
        if (!Files.exists(events,LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(events,LinkOption.NOFOLLOW_LINKS)) throw new IOException("source journal directory unsafe");
        List<Path> paths;
        try(var stream=Files.list(events)) { paths=stream.limit(4097).sorted().toList(); }
        if(paths.size()>4096) throw new IOException("source journal segment capacity exceeded");
        for(int i=0;i<paths.size();i++) {
            Path path=paths.get(i);DurableJson.rejectLinks(path);
            if(!path.getFileName().toString().equals(String.format("segment-%06d.wal",i+1))
                    || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("source journal segments incomplete or unsafe");
        }
        return paths;
    }
    private List<Stamp> stamp(Path dir) throws IOException {
        List<Stamp> result=new ArrayList<>();
        for(Path path:segments(dir)) {
            BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            Object changed;
            try { changed=Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS); }
            catch(UnsupportedOperationException | IllegalArgumentException e) { changed=UUID.randomUUID(); } // Revalidate when no reliable change indicator exists.
            result.add(new Stamp(path.getFileName().toString(),a.size(),a.fileKey(),a.lastModifiedTime(),changed));
        }
        return List.copyOf(result);
    }
    private static void remember(Key key,Watermark value) {
        synchronized(WATERMARKS) {
            WATERMARKS.put(key,value);
            while(WATERMARKS.size()>128) WATERMARKS.remove(WATERMARKS.keySet().iterator().next());
        }
    }
    private long watermark(Path dir,String book) throws IOException {
        Key key=key(dir,book); List<Stamp> stamp=stamp(dir);Watermark hit;
        synchronized(WATERMARKS) { hit=WATERMARKS.get(key); }
        if(hit!=null && hit.stamp().equals(stamp)) return hit.value();
        long max=0;
        for(var event:readAll(dir,book)) max=Math.max(max,event.sourceSeq());
        remember(key,new Watermark(stamp(dir),max));return max;
    }
    public long nextSourceSeq(Path dir,String book) {
        synchronized(publicationMonitor(dir)) {
            try { long next=Math.addExact(watermark(dir,book),1);remember(key(dir,book),new Watermark(stamp(dir),next));return next; }
            catch(Exception invalid) { throw unavailable(); }
        }
    }
    public long currentSourceSeq(Path dir,String book) {
        synchronized(publicationMonitor(dir)) {
            try { return watermark(dir,book); } catch(Exception invalid) { throw unavailable(); }
        }
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"来源事件记录不可验证，未重置序号；请先恢复数据");
    }
    public SourceChange prepare(Path dir,String book,String kind,Integer page,UUID commitId,String metadataId,
                                Integer beforeRevision,Integer afterRevision,String beforeHash,String afterHash,String reason) throws IOException {
        synchronized(publicationMonitor(dir)) {
            long seq=nextSourceSeq(dir,book);
            var event=new SourceChange(seq,kind,book,page,commitId,metadataId,beforeRevision,afterRevision,beforeHash,afterHash,reason,"PREPARED",Instant.now());
            validate(event,book);append(dir,book,event);return event;
        }
    }
    public SourceChange commit(Path dir,String book,long seq,Integer page,UUID commitId,int revision,String afterHash) throws IOException {
        synchronized(publicationMonitor(dir)) {
            var prepared=findIntent(dir,book,seq);
            if(!Objects.equals(prepared.commitId(),commitId) || !Objects.equals(prepared.afterRevision(),revision)
                    || !Objects.equals(prepared.afterHash(),afterHash) || page!=null && !Objects.equals(prepared.pageNumber(),page))
                throw new IOException("source settlement identity mismatch");
            return settle(dir,book,prepared,"COMMITTED");
        }
    }
    public SourceChange commit(Path dir,String book,long seq,UUID commitId,int revision,String afterHash) throws IOException {
        return commit(dir,book,seq,null,commitId,revision,afterHash);
    }
    public SourceChange markNotPublished(Path dir,String book,long seq,Integer page) throws IOException {
        synchronized(publicationMonitor(dir)) {
            var prepared=findIntent(dir,book,seq);
            if(page!=null && !Objects.equals(page,prepared.pageNumber())) throw new IOException("source target mismatch");
            return settle(dir,book,prepared,"NOT_PUBLISHED");
        }
    }
    public SourceChange markNotPublished(Path dir,String book,long seq) throws IOException { return markNotPublished(dir,book,seq,null); }
    private SourceChange findIntent(Path dir,String book,long seq) throws IOException {
        return readAll(dir,book).stream().filter(e->e.sourceSeq()==seq && "PREPARED".equals(e.state())).findFirst()
                .orElseThrow(()->new IOException("source intent missing"));
    }
    private SourceChange settle(Path dir,String book,SourceChange intent,String state) throws IOException {
        var events=readAll(dir,book);
        var latest=events.stream().filter(e->e.sourceSeq()==intent.sourceSeq()).reduce((a,b)->b).orElse(intent);
        if(state.equals(latest.state())) return latest;
        if(!Set.of("PREPARED","UNKNOWN").contains(latest.state())) throw new IOException("source outcome already settled");
        var event=new SourceChange(intent.sourceSeq(),intent.targetKind(),book,intent.pageNumber(),intent.commitId(),
                intent.metadataChangeId(),intent.beforeRevision(),intent.afterRevision(),intent.beforeHash(),intent.afterHash(),
                intent.changeReason(),state,Instant.now());
        append(dir,book,event);return event;
    }
    private void append(Path dir,String book,SourceChange event) throws IOException {
        byte[] payload=json.writeValueAsBytes(event);
        if(payload.length>DurableEventJournal.MAX_FRAME_PAYLOAD_BYTES) throw new IOException("source event too large");
        Path events=eventsDir(dir);DurableJson.rejectLinks(events);Files.createDirectories(events);
        List<Path> paths=segments(dir);Path active;
        if(paths.isEmpty()) {
            active=events.resolve("segment-000001.wal");wal.initSegment(active,book,UUID.randomUUID(),event.sourceSeq(),"0".repeat(64));
        } else {
            active=paths.get(paths.size()-1);var scan=wal.readSegment(active,book,true,new ArrayList<>());
            if(scan.frames().size()>=DurableEventJournal.MAX_SEGMENT_RECORDS
                    || scan.validLength()+payload.length+20>DurableEventJournal.MAX_SEGMENT_BYTES) {
                String previousHash=DurableEventJournal.sha256Hex(active);
                active=events.resolve(String.format("segment-%06d.wal",paths.size()+1));
                wal.initSegment(active,book,UUID.randomUUID(),event.sourceSeq(),previousHash);
            }
        }
        wal.append(active,event.sourceSeq(),payload);
        // Invalidate all handles' cached frontier; failures never cache a made-up zero.
        synchronized(WATERMARKS) { WATERMARKS.remove(key(dir,book)); }
    }
    public List<SourceChange> readAll(Path dir,String book) throws IOException {
        synchronized(publicationMonitor(dir)) {
            List<Path> paths=segments(dir);List<SourceChange> result=new ArrayList<>();long bytes=0;
            for(int i=0;i<paths.size();i++) {
                Path path=paths.get(i);bytes=Math.addExact(bytes,Files.size(path));
                if(bytes>MAX_REPLAY_BYTES) throw new IOException("source journal replay budget exceeded");
                var scan=wal.readSegment(path,book,i==paths.size()-1,new ArrayList<>());
                String previous=scan.header().prevSegmentSha256();
                if(i>0 && !"chained".equals(previous) && !previous.equals(DurableEventJournal.sha256Hex(paths.get(i-1))))
                    throw new IOException("source journal segment chain mismatch");
                for(var frame:scan.frames()) {
                    try(var parser=json.getFactory().createParser(frame.payload())) {
                        parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                        com.fasterxml.jackson.databind.JsonNode tree=json.readTree(parser);
                        if(tree==null || !tree.isObject() || parser.nextToken()!=null) throw new IOException("source event invalid");
                        for(String f:List.of("sourceSeq","pageNumber","beforeRevision","afterRevision")) {
                            var v=tree.get(f);
                            if(v!=null && !v.isNull() && (!v.isIntegralNumber() || !v.canConvertToLong()
                                    || !f.equals("sourceSeq") && !v.canConvertToInt())) throw new IOException("source event integer invalid");
                        }
                        var event=json.treeToValue(tree,SourceChange.class);validate(event,book);
                        if(event.sourceSeq()!=frame.seq()) throw new IOException("source frame identity mismatch");
                        result.add(event);
                    } catch(RuntimeException invalid) { throw new IOException("source event unreadable"); }
                }
            }
            return List.copyOf(result);
        }
    }
    private static void validate(SourceChange e,String book) throws IOException {
        if(e==null || !book.equals(e.bookId()) || e.sourceSeq()<1 || e.timestamp()==null
                || e.targetKind()==null || !e.targetKind().matches("[A-Z][A-Z_]{0,39}")
                || e.state()==null || !Set.of("PREPARED","COMMITTED","NOT_PUBLISHED","UNKNOWN").contains(e.state())
                || e.pageNumber()!=null && e.pageNumber()<1 || e.beforeRevision()!=null && e.beforeRevision()<0
                || e.afterRevision()!=null && e.afterRevision()<0) throw new IOException("source event identity invalid");
    }
    public List<SourceChange> readSince(Path dir,String book,long since,int limit) throws IOException {
        if(since<0 || limit<1 || limit>100000) throw new IllegalArgumentException("invalid source event window");
        return readAll(dir,book).stream().filter(e->e.sourceSeq()>since).limit(limit).toList();
    }
    public SourceChange latest(Path dir,String book) throws IOException {
        var all=readAll(dir,book);return all.isEmpty()?null:all.get(all.size()-1);
    }
    private Map<Long,SourceChange> unresolved(List<SourceChange> events) throws IOException {
        Map<Long,SourceChange> pending=new LinkedHashMap<>();
        for(var e:events) {
            if(Set.of("PREPARED","UNKNOWN").contains(e.state())) pending.put(e.sourceSeq(),e);
            else {
                var p=pending.get(e.sourceSeq());
                if(p!=null) {
                    if(e.commitId()!=null && !Objects.equals(e.commitId(),p.commitId())
                            || e.pageNumber()!=null && !Objects.equals(e.pageNumber(),p.pageNumber())
                            || "COMMITTED".equals(e.state()) && (!Objects.equals(e.afterHash(),p.afterHash())
                            || !Objects.equals(e.afterRevision(),p.afterRevision()))) throw new IOException("source settlement contradicts intent");
                    pending.remove(e.sourceSeq());
                }
            }
        }
        return pending;
    }
    public boolean hasUnresolved(Path dir,String book) throws IOException { return !unresolved(readAll(dir,book)).isEmpty(); }
    /** Only exact page or already committed journal evidence proves publication; higher revisions do not. */
    public void reconcile(Path dir,String book,BookStore store) throws IOException {
        // Caller holds BookStore's directory monitor; do not acquire that monitor after the source monitor.
        synchronized(publicationMonitor(dir)) {
            for(var intent:unresolved(readAll(dir,book)).values()) {
                if(!"PAGE".equals(intent.targetKind()) || intent.pageNumber()==null) continue;
                Page page=store.readPage(book,intent.pageNumber());String state="UNKNOWN";
                if(page!=null) {
                    String hash=store.pageCommitJournal().hash(page);int revision=BookStore.revisionOrZero(page);
                    boolean exact=intent.commitId()!=null && intent.commitId().equals(page.lastCommitId())
                            && Objects.equals(intent.afterRevision(),revision) && Objects.equals(intent.afterHash(),hash);
                    if(!exact && intent.commitId()!=null) {
                        exact=store.pageCommitJournal().read(dir,book,intent.pageNumber()).stream().anyMatch(e->
                                "COMMITTED".equals(e.state()) && intent.commitId().equals(e.commitId())
                                && Objects.equals(intent.afterRevision(),e.publishedRevision()) && Objects.equals(intent.afterHash(),e.contentHash()));
                    }
                    if(exact) state="COMMITTED";
                    else if(Objects.equals(intent.beforeRevision(),revision) && Objects.equals(intent.beforeHash(),hash)) state="NOT_PUBLISHED";
                }
                settle(dir,book,intent,state);
            }
        }
    }
}
