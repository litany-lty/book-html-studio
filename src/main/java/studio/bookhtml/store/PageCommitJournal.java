package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import studio.bookhtml.domain.Page;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Per-page log: exact commit ID, revision and content hash prove a publication.
 * Recovery settles metadata only; it never regenerates text or calls a provider. */
final class PageCommitJournal {
    static final int MAX_RECORDS = 16, MAX_BYTES = 65536;
    static final Set<String> STATES = Set.of("PREPARED", "COMMITTED", "NOT_PUBLISHED", "UNKNOWN");
    record Entry(UUID commitId, String bookId, int pageNumber, UUID attemptId, long attemptSeq,
                 String operation, String outcome, int expectedRevision, int publishedRevision,
                 String previousHash, String contentHash, String state, Instant createdAt) {
        Entry state(String value) {
            return new Entry(commitId,bookId,pageNumber,attemptId,attemptSeq,operation,outcome,
                    expectedRevision,publishedRevision,previousHash,contentHash,value,createdAt);
        }
    }
    record Journal(int schemaVersion, List<Entry> entries) {}
    private final ObjectMapper json;
    PageCommitJournal(ObjectMapper json) { this.json = json; }
    static Path path(Path bookDir, int page) { return bookDir.resolve("pages/commits").resolve(page + ".json"); }
    List<Entry> read(Path dir, String bookId, int page) throws IOException {
        Path path = path(dir, page);
        DurableJson.rejectLinks(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("commit journal unsafe");
        byte[] data;
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { data = in.readNBytes(MAX_BYTES + 1); }
        if (data.length > MAX_BYTES) throw new IOException("commit journal exceeds bound");
        try (var parser = json.getFactory().createParser(data)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode tree = json.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null
                    || !tree.path("schemaVersion").isIntegralNumber() || !tree.path("schemaVersion").canConvertToInt()
                    || tree.path("schemaVersion").intValue() != 1 || !tree.path("entries").isArray()
                    || tree.path("entries").size() > MAX_RECORDS) throw new IOException("commit journal invalid");
            for (var entry:tree.path("entries")) {
                for (String field:List.of("pageNumber","attemptSeq","expectedRevision","publishedRevision")) {
                    var value=entry.path(field);
                    if (!value.isIntegralNumber() || !value.canConvertToLong()
                            || (!field.equals("attemptSeq") && !value.canConvertToInt()))
                        throw new IOException("invalid commit journal integer");
                }
            }
            Journal journal = json.treeToValue(tree, Journal.class);
            Set<UUID> ids = new HashSet<>();
            for (Entry e : journal.entries()) {
                if (e == null || e.commitId() == null || !ids.add(e.commitId()) || !bookId.equals(e.bookId())
                        || page != e.pageNumber() || e.expectedRevision() < 0
                        || (long)e.publishedRevision() != (long)e.expectedRevision()+1 || e.createdAt() == null
                        || e.operation() == null || e.outcome() == null || e.state() == null || !STATES.contains(e.state())
                        || !validHash(e.previousHash()) || !validHash(e.contentHash())
                        || e.attemptId() == null && e.attemptSeq() != 0 || e.attemptId() != null && e.attemptSeq() < 1)
                    throw new IOException("commit journal invalid");
                CommitOp.valueOf(e.operation());
                if (!Set.of("SUCCEEDED","PARTIAL","FAILED","BASELINE_PUBLISHED").contains(e.outcome()))
                    throw new IOException("commit outcome invalid");
            }
            return List.copyOf(journal.entries());
        } catch (Exception invalid) { throw new IOException("commit journal unreadable"); }
    }
    private static boolean validHash(String v) { return v != null && v.matches("[0-9a-f]{64}"); }
    void write(Path dir, int page, List<Entry> entries) throws IOException {
        if (entries.size() > MAX_RECORDS) throw new IOException("commit journal capacity exceeded");
        DurableJson.write(path(dir,page),new Journal(1,List.copyOf(entries)),json,MAX_BYTES);
    }
    String hash(Page page) throws IOException {
        try {
            // Fixed fields: commit identity and format-version metadata are excluded.
            byte[] value = json.writeValueAsBytes(Arrays.asList(page.pageNumber(),page.width(),page.height(),
                    page.status(),page.provider(),page.blocks(),page.warnings(),page.reviewed(),page.error(),
                    page.sourceRecords(),page.revision()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    boolean matches(Entry entry, Page page) throws IOException {
        return page != null && entry.commitId().equals(page.lastCommitId())
                && entry.publishedRevision() == BookStore.revisionOrZero(page) && entry.contentHash().equals(hash(page));
    }
    List<Entry> reconcile(Path dir, String bookId, int page, Page published) throws IOException {
        List<Entry> before = read(dir,bookId,page), after = new ArrayList<>(before.size());
        for (Entry e : before) {
            if (!"PREPARED".equals(e.state())) { after.add(e); continue; }
            String state = matches(e,published) ? "COMMITTED"
                    : published != null && BookStore.revisionOrZero(published)==e.expectedRevision()
                    && e.previousHash().equals(hash(published)) ? "NOT_PUBLISHED" : "UNKNOWN";
            after.add(e.state(state));
        }
        if (!after.equals(before)) write(dir,page,after);
        return List.copyOf(after);
    }
    List<Entry> append(List<Entry> before, Entry next) throws IOException {
        var entries = new ArrayList<>(before);
        if (entries.stream().anyMatch(e -> "PREPARED".equals(e.state()) || "UNKNOWN".equals(e.state())))
            throw new IOException("unsettled commit requires recovery");
        while (entries.size() >= MAX_RECORDS) entries.remove(0);
        entries.add(next);
        return List.copyOf(entries);
    }
    List<Entry> mark(List<Entry> entries, UUID id, String state) {
        return entries.stream().map(e -> e.commitId().equals(id) ? e.state(state) : e).toList();
    }
}
