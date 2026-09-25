package studio.bookhtml.store;

import studio.bookhtml.domain.SourceChange;
import java.io.IOException;
import java.util.*;

/** Bounded derived reduction. WAL and exact page commit identities remain authoritative. */
final class SourceReplayState {
    static final int MAX_PENDING=512, MAX_RECENT=128;
    static final long MAX_BYTES=2L*1024*1024;
    record Operation(SourceChange prepared,SourceChange latest) {}
    final LinkedHashMap<Long,Operation> pending=new LinkedHashMap<>();
    final LinkedHashMap<Long,Operation> recent=new LinkedHashMap<>();
    long maxSeq;
    SourceChange last;
    SourceReplayState copy() {
        var result=new SourceReplayState();result.pending.putAll(pending);result.recent.putAll(recent);
        result.maxSeq=maxSeq;result.last=last;return result;
    }
    Operation operation(long seq) { var value=pending.get(seq);return value!=null?value:recent.get(seq); }
    static boolean terminal(SourceChange event) { return Set.of("COMMITTED","NOT_PUBLISHED").contains(event.state()); }
    private static boolean sameIntent(SourceChange a,SourceChange b) {
        return Objects.equals(a.bookId(),b.bookId()) && Objects.equals(a.targetKind(),b.targetKind())
                && Objects.equals(a.pageNumber(),b.pageNumber()) && Objects.equals(a.commitId(),b.commitId())
                && Objects.equals(a.metadataChangeId(),b.metadataChangeId())
                && Objects.equals(a.beforeRevision(),b.beforeRevision()) && Objects.equals(a.afterRevision(),b.afterRevision())
                && Objects.equals(a.beforeHash(),b.beforeHash()) && Objects.equals(a.afterHash(),b.afterHash());
    }
    static void compatible(SourceChange prepared,SourceChange result) throws IOException {
        // Existing early settlement records omitted before-fields and sometimes pageNumber.
        // Keep that readable without interpreting an unrelated commit as proof of publication.
        if(result.commitId()!=null && !Objects.equals(result.commitId(),prepared.commitId())
                || result.pageNumber()!=null && !Objects.equals(result.pageNumber(),prepared.pageNumber())
                || "COMMITTED".equals(result.state()) && (!Objects.equals(result.afterRevision(),prepared.afterRevision())
                || !Objects.equals(result.afterHash(),prepared.afterHash()))) throw new IOException("source settlement contradicts intent");
    }
    void apply(SourceChange event) throws IOException { reduce(event,true); }
    // Previously persisted backlogs remain recoverable even when they cannot fit
    // the optional hot cache. Never discard their unknown operations to make room.
    void replay(SourceChange event) throws IOException { reduce(event,false); }
    boolean cacheable() {
        try { checkBounds();return true; } catch(IOException capacity) { return false; }
    }
    private void reduce(SourceChange event,boolean enforceCapacity) throws IOException {
        long seq=event.sourceSeq();var old=operation(seq);Operation next;
        if("PREPARED".equals(event.state())) {
            if(old==null && seq<=maxSeq) throw new IOException("source preparation reused an earlier sequence");
            if(old!=null && (old.prepared()==null || terminal(old.latest()) || !sameIntent(old.prepared(),event)))
                throw new IOException("source sequence has contradictory preparation");
            next=old==null?new Operation(event,event):old;
        } else {
            if(old!=null) {
                if(old.prepared()!=null) compatible(old.prepared(),event);
                if(terminal(old.latest()) && !old.latest().state().equals(event.state()))
                    throw new IOException("source outcome cannot change after settlement");
            }
            next=new Operation(old==null?null:old.prepared(),event);
        }
        pending.remove(seq);recent.remove(seq);
        if(terminal(next.latest())) {
            recent.put(seq,next);
            while(recent.size()>MAX_RECENT) recent.remove(recent.keySet().iterator().next());
        } else pending.put(seq,next);
        maxSeq=Math.max(maxSeq,seq);last=event;if(enforceCapacity) checkBounds();
    }
    void checkBounds() throws IOException {
        if(pending.size()>MAX_PENDING || recent.size()>MAX_RECENT) throw new IOException("source recovery state capacity exceeded");
        long bytes=0;
        for(var value:pending.values()) bytes+=weight(value);
        for(var value:recent.values()) bytes+=weight(value);
        if(bytes>MAX_BYTES) throw new IOException("source recovery state byte budget exceeded");
    }
    private static long weight(Operation value) {
        return 96+weight(value.prepared())+(value.latest()==value.prepared()?0:weight(value.latest()));
    }
    private static long weight(SourceChange e) {
        if(e==null) return 0;
        long bytes=320;
        for(String value:new String[]{e.targetKind(),e.bookId(),e.metadataChangeId(),e.beforeHash(),e.afterHash(),e.changeReason(),e.state()})
            if(value!=null) bytes+=40+2L*value.length();
        return bytes;
    }
    static SourceReplayState restore(String book,long maxSeq,SourceChange last,List<Operation> open,List<Operation> settled) throws IOException {
        if(maxSeq<0 || open==null || settled==null || open.size()>MAX_PENDING || settled.size()>MAX_RECENT
                || maxSeq==0 && last!=null || maxSeq>0 && last==null) throw new IOException("invalid source checkpoint reduction");
        var result=new SourceReplayState();Set<Long> seen=new HashSet<>();
        for(var list:List.of(open,settled)) for(var op:list) {
            if(op==null || op.latest()==null) throw new IOException("invalid checkpoint operation");
            SourceChangeJournal.validate(op.latest(),book);
            long seq=op.latest().sourceSeq();
            if(seq>maxSeq || !seen.add(seq) || terminal(op.latest())!=(list==settled)) throw new IOException("invalid checkpoint operation membership");
            if(op.prepared()!=null) {
                SourceChangeJournal.validate(op.prepared(),book);
                if(!"PREPARED".equals(op.prepared().state()) || op.prepared().sourceSeq()!=seq)
                    throw new IOException("invalid checkpoint preparation");
                compatible(op.prepared(),op.latest());
            } else if("PREPARED".equals(op.latest().state())) throw new IOException("missing checkpoint preparation");
            (terminal(op.latest())?result.recent:result.pending).put(seq,op);
        }
        if(last!=null) { SourceChangeJournal.validate(last,book);if(last.sourceSeq()>maxSeq) throw new IOException("invalid checkpoint last event"); }
        result.maxSeq=maxSeq;result.last=last;result.checkBounds();return result;
    }
}
