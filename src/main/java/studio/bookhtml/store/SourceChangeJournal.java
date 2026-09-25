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
    private record Snapshot(List<Stamp> stamp, List<SourceJournalCheckpoint.Seal> seals,
                            SourceReplayState sealed, SourceReplayState full, int activeFrames, long activeLength) {}
    private static final Map<Key,Snapshot> SNAPSHOTS=new LinkedHashMap<>(8,.75f,true);
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SourceChangeJournal.class);
    private final ObjectMapper json;
    private final SourceJournalCheckpoint checkpoints;
    private final DurableEventJournal wal=new DurableEventJournal();
    public SourceChangeJournal(ObjectMapper json) { this.json=Objects.requireNonNull(json); this.checkpoints=new SourceJournalCheckpoint(json); }
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
    private static boolean sameDuringRead(Stamp before,Stamp after) {
        return before.name().equals(after.name()) && before.size()==after.size()
                && Objects.equals(before.fileKey(),after.fileKey()) && Objects.equals(before.modified(),after.modified())
                && (before.changed() instanceof UUID || after.changed() instanceof UUID || Objects.equals(before.changed(),after.changed()));
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
        long max=snapshot(dir,book).full().maxSeq;
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
        var operation=snapshot(dir,book).full().operation(seq);
        if(operation!=null && operation.prepared()!=null) return operation.prepared();
        // Rare replay of an old settled operation falls back to authority; recent/pending
        // state remains bounded and is not confused with missing history.
        return readAll(dir,book).stream().filter(e->e.sourceSeq()==seq && "PREPARED".equals(e.state())).findFirst()
                .orElseThrow(()->new IOException("source intent missing"));
    }
    private SourceChange settle(Path dir,String book,SourceChange intent,String state) throws IOException {
        var operation=snapshot(dir,book).full().operation(intent.sourceSeq());
        var latest=operation==null?readAll(dir,book).stream().filter(e->e.sourceSeq()==intent.sourceSeq())
                .reduce((a,b)->b).orElse(intent):operation.latest();
        if(state.equals(latest.state())) return latest;
        if(!Set.of("PREPARED","UNKNOWN").contains(latest.state())) throw new IOException("source outcome already settled");
        var event=new SourceChange(intent.sourceSeq(),intent.targetKind(),book,intent.pageNumber(),intent.commitId(),
                intent.metadataChangeId(),intent.beforeRevision(),intent.afterRevision(),intent.beforeHash(),intent.afterHash(),
                intent.changeReason(),state,Instant.now());
        append(dir,book,event);return event;
    }
    private static void rememberSnapshot(Key key,Snapshot value) {
        synchronized(SNAPSHOTS) {
            if(!value.full().cacheable() || !value.sealed().cacheable()) { SNAPSHOTS.remove(key);return; }
            SNAPSHOTS.put(key,value);
            while(SNAPSHOTS.size()>8) SNAPSHOTS.remove(SNAPSHOTS.keySet().iterator().next());
        }
    }
    private static void invalidate(Key key) {
        synchronized(SNAPSHOTS) { SNAPSHOTS.remove(key); }
        synchronized(WATERMARKS) { WATERMARKS.remove(key); }
    }
    private void checkpoint(Path dir,String book,List<SourceJournalCheckpoint.Seal> seals,SourceReplayState sealed) {
        if(seals.isEmpty() || Thread.currentThread().isInterrupted()) return;
        try { checkpoints.save(dir,book,seals,sealed); }
        catch(IOException | RuntimeException unavailable) {
            LOG.debug("SOURCE_CHECKPOINT_DEFERRED: authoritative events retained; no operation resent");
        }
    }
    /** A metadata fingerprint is only reused inside this process after actual WAL verification.
     * Cold checkpoints validate every sealed file's hash; an active tail is replayed independently.
     */
    private Snapshot snapshot(Path dir,String book) throws IOException {
        Key key=key(dir,book);List<Stamp> current=stamp(dir);Snapshot cached;
        long bytes=0;for(var item:current) bytes=Math.addExact(bytes,item.size());
        if(bytes>MAX_REPLAY_BYTES) throw new IOException("source journal replay budget exceeded");
        synchronized(SNAPSHOTS) { cached=SNAPSHOTS.get(key); }
        if(current.isEmpty() && Files.exists(SourceJournalCheckpoint.path(dir),LinkOption.NOFOLLOW_LINKS)
                || cached!=null && (current.size()<cached.stamp().size()
                || !current.isEmpty() && current.size()==cached.stamp().size()
                && current.get(current.size()-1).size()<cached.activeLength()))
            throw new IOException("previously verified source history is missing or shortened");
        if(cached!=null && cached.stamp().equals(current)) return cached;
        try {
            List<Path> paths=current.stream().map(st->eventsDir(dir).resolve(st.name())).toList();
            List<SourceJournalCheckpoint.Seal> seals=new ArrayList<>();
            SourceReplayState full=new SourceReplayState(),sealed=new SourceReplayState();
            int start=0,frames=0;long activeLength=0;boolean checkpointNeeded=false,tailRepaired=false;
            if(cached!=null && !current.isEmpty() && cached.stamp().size()==current.size()
                    && cached.stamp().subList(0,current.size()-1).equals(current.subList(0,current.size()-1))) {
                seals.addAll(cached.seals());sealed=cached.sealed();full=sealed.copy();start=seals.size();
            } else {
                var loaded=checkpoints.load(dir,book,paths);
                if(loaded!=null) { seals.addAll(loaded.seals());full=loaded.state();start=seals.size(); }
                checkpointNeeded=start<Math.max(0,paths.size()-1) || loaded==null;
            }
            for(int i=start;i<paths.size();i++) {
                Path path=paths.get(i);boolean active=i==paths.size()-1;
                if(active) sealed=full.copy();
                var scan=wal.readSegment(path,book,active,new ArrayList<>());
                String previous=scan.header().prevSegmentSha256();
                if(i>0 && !"chained".equals(previous) && !previous.equals(seals.get(i-1).sha256()))
                    throw new IOException("source journal segment chain mismatch");
                for(var frame:scan.frames()) full.replay(decode(frame,book));
                if(active) { frames=scan.frames().size();activeLength=scan.validLength();tailRepaired=scan.tailTruncated(); }
                else seals.add(new SourceJournalCheckpoint.Seal(path.getFileName().toString(),scan.validLength(),DurableEventJournal.sha256Hex(path)));
            }
            List<Stamp> after=stamp(dir);
            // Only the final incomplete frame may have been repaired by the active-tail reader.
            if(after.size()!=current.size()) throw new IOException("source journal changed during verification");
            for(int i=0;i<after.size();i++) {
                boolean active=i==after.size()-1;
                if(active && tailRepaired) {
                    if(after.get(i).size()!=activeLength || !Objects.equals(after.get(i).fileKey(),current.get(i).fileKey()))
                        throw new IOException("source tail changed during repair");
                } else if(!sameDuringRead(current.get(i),after.get(i))) throw new IOException("source journal changed during verification");
            }
            var result=new Snapshot(after,List.copyOf(seals),sealed,full,frames,activeLength);
            rememberSnapshot(key,result);
            if(checkpointNeeded) checkpoint(dir,book,seals,sealed);
            return result;
        } catch(IOException | RuntimeException failure) { invalidate(key);throw failure; }
    }
    private void append(Path dir,String book,SourceChange event) throws IOException {
        byte[] payload=json.writeValueAsBytes(event);
        if(payload.length>DurableEventJournal.MAX_FRAME_PAYLOAD_BYTES) throw new IOException("source event too large");
        Snapshot verified=snapshot(dir,book);
        SourceReplayState next=verified.full().copy();
        if(!verified.full().cacheable() && !"PREPARED".equals(event.state())) next.replay(event);
        else next.apply(event); // New admissions obey the cache budget; old debt may still be settled.
        Path events=eventsDir(dir);DurableJson.rejectLinks(events);Files.createDirectories(events);
        List<SourceJournalCheckpoint.Seal> seals=new ArrayList<>(verified.seals());
        SourceReplayState sealed=verified.sealed();int frames=verified.activeFrames();
        int count=verified.stamp().size();boolean newSegment=count==0 || frames>=DurableEventJournal.MAX_SEGMENT_RECORDS
                || verified.activeLength()+payload.length+20>DurableEventJournal.MAX_SEGMENT_BYTES;
        long total=verified.stamp().stream().mapToLong(Stamp::size).sum()+payload.length+20+(newSegment?512:0);
        if(total>MAX_REPLAY_BYTES || newSegment && count>=4096) throw new IOException("source journal capacity exceeded");
        Path active=count==0?null:events.resolve(String.format("segment-%06d.wal",count));
        try {
            if(newSegment) {
                String previous=active==null?"0".repeat(64):DurableEventJournal.sha256Hex(active);
                if(active!=null) { seals.add(new SourceJournalCheckpoint.Seal(active.getFileName().toString(),verified.activeLength(),previous));sealed=verified.full().copy(); }
                active=events.resolve(String.format("segment-%06d.wal",count+1));
                wal.initSegment(active,book,UUID.randomUUID(),event.sourceSeq(),previous);frames=0;
            }
            long length=wal.append(active,event.sourceSeq(),payload);
            rememberSnapshot(key(dir,book),new Snapshot(stamp(dir),List.copyOf(seals),sealed,next,frames+1,length));
            synchronized(WATERMARKS) { WATERMARKS.remove(key(dir,book)); }
            if(newSegment) checkpoint(dir,book,seals,sealed);
        } catch(IOException | RuntimeException failure) { invalidate(key(dir,book));throw failure; }
    }
    private SourceChange decode(DurableEventJournal.JournalFrame frame,String book) throws IOException {
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
            return event;
        } catch(RuntimeException invalid) { throw new IOException("source event unreadable"); }
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
                for(var frame:scan.frames()) result.add(decode(frame,book));
            }
            return List.copyOf(result);
        }
    }
    static void validate(SourceChange e,String book) throws IOException {
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
        synchronized(publicationMonitor(dir)) { return snapshot(dir,book).full().last; }
    }
    public boolean hasUnresolved(Path dir,String book) throws IOException {
        synchronized(publicationMonitor(dir)) { return !snapshot(dir,book).full().pending.isEmpty(); }
    }
    /** Only exact page or already committed journal evidence proves publication; higher revisions do not. */
    public void reconcile(Path dir,String book,BookStore store) throws IOException {
        // Caller holds BookStore's directory monitor; do not acquire that monitor after the source monitor.
        synchronized(publicationMonitor(dir)) {
            for(var operation:List.copyOf(snapshot(dir,book).full().pending.values())) {
                var intent=operation.latest();
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
