package studio.bookhtml.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import studio.bookhtml.domain.SourceChange;
import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Optional, checksummed reduction of an exact sealed WAL prefix. Never deletes source events. */
final class SourceJournalCheckpoint {
    static final int MAX_BYTES=4*1024*1024;
    record Seal(String name,long length,String sha256) {}
    record Data(int schemaVersion,String bookId,List<Seal> sealedSegments,long sourceSeq,
                List<SourceReplayState.Operation> pending,List<SourceReplayState.Operation> recent,
                SourceChange lastEvent,String checksum) {
        Data checksum(String value) { return new Data(schemaVersion,bookId,sealedSegments,sourceSeq,pending,recent,lastEvent,value); }
    }
    record Loaded(List<Seal> seals,SourceReplayState state) {}
    private final ObjectMapper json;
    SourceJournalCheckpoint(ObjectMapper json) {
        this.json=json.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
    static Path path(Path dir) { return dir.resolve("source-checkpoint.json"); }
    private String digest(Data data) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data.checksum("")))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static final class MissingPrefix extends IOException {
        MissingPrefix() { super("source journal prefix or active tail is missing; checkpoint retained"); }
    }
    Loaded load(Path dir,String book,List<Path> sourceFiles) throws IOException {
        try {
            Path file=path(dir);DurableJson.rejectLinks(file);
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>MAX_BYTES) return null;
            byte[] bytes;
            try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=in.readNBytes(MAX_BYTES+1); }
            if(bytes.length>MAX_BYTES) return null;
            Data data;
            try(var parser=json.getFactory().createParser(bytes)) {
                parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                data=json.readValue(parser,Data.class);
                if(parser.nextToken()!=null) return null;
            }
            if(data==null || data.schemaVersion()!=1 || !book.equals(data.bookId()) || data.sealedSegments()==null
                    || data.sealedSegments().isEmpty()
                    || data.sealedSegments().size()>4095 || data.checksum()==null
                    || !data.checksum().matches("[0-9a-f]{64}") || !digest(data).equals(data.checksum())) return null;
            var state=SourceReplayState.restore(book,data.sourceSeq(),data.lastEvent(),data.pending(),data.recent());
            if(data.sealedSegments().size()>=sourceFiles.size()) throw new MissingPrefix();
            for(int i=0;i<data.sealedSegments().size();i++) {
                Seal seal=data.sealedSegments().get(i);Path source=sourceFiles.get(i);
                if(seal==null || !String.format("segment-%06d.wal",i+1).equals(seal.name())
                        || !source.getFileName().toString().equals(seal.name()) || seal.length()<1
                        || seal.length()>DurableEventJournal.MAX_SEGMENT_BYTES+DurableEventJournal.MAX_FRAME_PAYLOAD_BYTES+512
                        || seal.sha256()==null || !seal.sha256().matches("[0-9a-f]{64}")) return null;
                DurableJson.rejectLinks(source);
                if(Files.size(source)!=seal.length() || !DurableEventJournal.sha256Hex(source).equals(seal.sha256())) return null;
            }
            return new Loaded(List.copyOf(data.sealedSegments()),state);
        } catch(MissingPrefix missing) { throw missing; }
        catch(Exception invalid) { return null; } // Re-read authoritative WAL, never reset it or trust an unverified checkpoint.
    }
    void save(Path dir,String book,List<Seal> seals,SourceReplayState state) throws IOException {
        if(seals.isEmpty()) return;
        state.checkBounds();
        var pending=state.pending.values().stream().sorted(Comparator.comparingLong(o->o.latest().sourceSeq())).toList();
        Data data=new Data(1,book,List.copyOf(seals),state.maxSeq,pending,List.copyOf(state.recent.values()),state.last,"");
        DurableJson.write(path(dir),data.checksum(digest(data)),json,MAX_BYTES);
    }
}
