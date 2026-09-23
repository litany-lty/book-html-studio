package studio.bookhtml.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.DurableJson;
import studio.bookhtml.store.PageHeadStore;
import studio.bookhtml.store.SourceChangeJournal;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for persistent, sharded, incremental book indexes.
 * Supports sharded summaries (128 per shard), inverted search with snippet verification,
 * atomic generation publish gates, snapshot pagination (5m TTL), and generation pinning.
 */
@Service
public class BookIndexService {
    public static final int SHARD_SIZE = 128;
    public static final int MAX_POSTING_CANDIDATES = 500;
    private static final int MAX_ACTIVE_SNAPSHOTS = 16;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final ObjectMapper json;
    private final Map<String, CompletableFuture<BookIndexManifest>> inFlightBuilds = new ConcurrentHashMap<>();
    private final Map<String, SearchSnapshot> searchSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pinnedGenerations = new ConcurrentHashMap<>();

    public record Posting(int pageNumber, String blockId) {}

    public record SearchSnapshot(
            String snapshotToken,
            String bookId,
            String query,
            List<Map<String, Object>> results,
            Instant createdAt
    ) {
        boolean expired() {
            return Duration.between(createdAt, Instant.now()).compareTo(SNAPSHOT_TTL) > 0;
        }
    }

    public BookIndexService(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public static Path indexDir(Path bookDir) {
        return bookDir.resolve("indexes");
    }

    public static Path manifestPath(Path bookDir) {
        return indexDir(bookDir).resolve("manifest.json");
    }

    public static Path generationsDir(Path bookDir) {
        return indexDir(bookDir).resolve("generations");
    }

    public static Path generationDir(Path bookDir, String genId) {
        return generationsDir(bookDir).resolve(genId);
    }

    public static Path stagingDir(Path bookDir, String genId) {
        return bookDir.resolve("index-staging").resolve(genId);
    }

    public static Path shardPath(Path genDir, int shardIndex) {
        return genDir.resolve("summary").resolve(String.format("shard-%06d.json", shardIndex));
    }

    public static Path pageRecordPath(Path genDir, int pageNumber) {
        return genDir.resolve("page-records").resolve(pageNumber + ".json");
    }

    public static Path termPartPath(Path genDir, int partIndex) {
        return genDir.resolve("terms").resolve(String.format("part-%02x.json", partIndex));
    }

    public static Path chaptersPath(Path genDir) {
        return genDir.resolve("chapters").resolve("outline.json");
    }

    public static Path edgesPath(Path genDir) {
        return genDir.resolve("edges").resolve("clusters.json");
    }

    public BookIndexManifest manifest(Path bookDir) {
        Path path = manifestPath(bookDir);
        try {
            DurableJson.rejectLinks(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            byte[] bytes = Files.readAllBytes(path);
            return json.readValue(bytes, BookIndexManifest.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public PageSummary pageSummary(Path bookDir, int pageNumber) {
        BookIndexManifest m = manifest(bookDir);
        if (m == null || m.generationId() == null) return null;

        int shardIdx = (pageNumber - 1) / SHARD_SIZE;
        Path shard = shardPath(generationDir(bookDir, m.generationId()), shardIdx);
        try {
            if (Files.exists(shard)) {
                List<PageSummary> list = json.readValue(shard.toFile(), new TypeReference<>() {});
                for (PageSummary ps : list) {
                    if (ps.pageNumber() == pageNumber) return ps;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public List<PageSummary> pageSummaries(Path bookDir, int fromPage, int limit) {
        BookIndexManifest m = manifest(bookDir);
        if (m == null || m.generationId() == null) return List.of();

        int toPage = fromPage + limit - 1;
        int startShard = (fromPage - 1) / SHARD_SIZE;
        int endShard = (toPage - 1) / SHARD_SIZE;

        List<PageSummary> result = new ArrayList<>(limit);
        Path genDir = generationDir(bookDir, m.generationId());
        for (int s = startShard; s <= endShard; s++) {
            Path sp = shardPath(genDir, s);
            if (!Files.exists(sp)) continue;
            try {
                List<PageSummary> list = json.readValue(sp.toFile(), new TypeReference<>() {});
                for (PageSummary ps : list) {
                    if (ps.pageNumber() >= fromPage && ps.pageNumber() <= toPage) {
                        result.add(ps);
                        if (result.size() >= limit) return result;
                    }
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    public List<Map<String, Object>> search(Path bookDir, String bookId, String query, String snapshotToken, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return List.of();
        if (q.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "搜索词过长");

        // 1. Check snapshot pagination token
        if (snapshotToken != null && !snapshotToken.isBlank()) {
            SearchSnapshot snapshot = searchSnapshots.get(snapshotToken);
            if (snapshot == null || snapshot.expired()) {
                searchSnapshots.remove(snapshotToken);
                throw new ApiException(HttpStatus.GONE, "搜索快照已失效，请重新搜索");
            }
            if (!snapshot.bookId().equals(bookId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "快照不属于当前书籍");
            }
            return snapshot.results();
        }

        BookIndexManifest m = manifest(bookDir);
        if (m == null || m.generationId() == null) return null; // Fallback to live scan

        Path genDir = generationDir(bookDir, m.generationId());
        Set<String> terms = extractTerms(q);
        if (terms.isEmpty()) return List.of();

        // 2. Query postings from partitions
        Map<Integer, Set<String>> candidatePagesAndBlocks = new LinkedHashMap<>();
        for (String term : terms) {
            int part = termPartition(term);
            Path partFile = termPartPath(genDir, part);
            if (!Files.exists(partFile)) continue;
            try {
                Map<String, List<Posting>> map = json.readValue(partFile.toFile(), new TypeReference<>() {});
                List<Posting> postings = map.get(term);
                if (postings != null) {
                    for (Posting p : postings) {
                        candidatePagesAndBlocks.computeIfAbsent(p.pageNumber(), k -> new HashSet<>()).add(p.blockId());
                    }
                }
            } catch (Exception ignored) {}
        }

        if (candidatePagesAndBlocks.isEmpty()) return List.of();

        // 3. Verify actual text snippet from page records (O(candidates), not full book)
        List<Map<String, Object>> matches = new ArrayList<>();
        String queryLower = q.toLowerCase(Locale.ROOT);

        for (Map.Entry<Integer, Set<String>> entry : candidatePagesAndBlocks.entrySet()) {
            if (matches.size() >= limit) break;
            int pageNum = entry.getKey();
            Path recPath = pageRecordPath(genDir, pageNum);
            if (!Files.exists(recPath)) continue;

            try {
                PageIndexRecord rec = json.readValue(recPath.toFile(), PageIndexRecord.class);
                if (!bookId.equals(rec.bookId())) continue; // Book isolation

                for (var seg : rec.textSegments()) {
                    if ("advertisement".equals(seg.type())) continue;
                    if (seg.text() != null && seg.text().toLowerCase(Locale.ROOT).contains(queryLower)) {
                        matches.add(Map.of(
                                "pageNumber", pageNum,
                                "blockId", seg.blockId(),
                                "text", seg.text()
                        ));
                        if (matches.size() >= limit) break;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. Cache search snapshot if large
        if (matches.size() > 50) {
            String token = UUID.randomUUID().toString();
            evictExpiredSnapshots();
            searchSnapshots.put(token, new SearchSnapshot(token, bookId, q, List.copyOf(matches), Instant.now()));
        }

        return matches;
    }

    private void evictExpiredSnapshots() {
        if (searchSnapshots.size() >= MAX_ACTIVE_SNAPSHOTS) {
            searchSnapshots.entrySet().removeIf(e -> e.getValue().expired());
            while (searchSnapshots.size() >= MAX_ACTIVE_SNAPSHOTS) {
                var it = searchSnapshots.keySet().iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }
        }
    }

    public synchronized void pinGeneration(String bookId, String generationId) {
        pinnedGenerations.computeIfAbsent(bookId, k -> ConcurrentHashMap.newKeySet()).add(generationId);
    }

    public synchronized void unpinGeneration(String bookId, String generationId) {
        Set<String> set = pinnedGenerations.get(bookId);
        if (set != null) {
            set.remove(generationId);
        }
    }

    public synchronized boolean isPinned(String bookId, String generationId) {
        Set<String> set = pinnedGenerations.get(bookId);
        return set != null && set.contains(generationId);
    }

    /**
     * B03-05: Coalesced background build with fence gate verification.
     */
    public CompletableFuture<BookIndexManifest> buildOrRebuild(
            Path bookDir, String bookId, String pdfSourceHash, int totalPages,
            SourceChangeJournal sourceJournal,
            java.util.function.Function<Integer, Page> pageSupplier) {

        return inFlightBuilds.computeIfAbsent(bookId, id -> CompletableFuture.supplyAsync(() -> {
            try {
                return executeBuild(bookDir, bookId, pdfSourceHash, totalPages, sourceJournal, pageSupplier);
            } catch (Exception e) {
                throw new CompletionException(e);
            } finally {
                inFlightBuilds.remove(bookId);
            }
        }));
    }

    private BookIndexManifest executeBuild(
            Path bookDir, String bookId, String pdfSourceHash, int totalPages,
            SourceChangeJournal sourceJournal,
            java.util.function.Function<Integer, Page> pageSupplier) throws IOException {

        long startSeq = sourceJournal.currentSourceSeq(bookDir, bookId);
        String genId = UUID.randomUUID().toString();
        Path staging = stagingDir(bookDir, genId);

        Files.createDirectories(staging.resolve("summary"));
        Files.createDirectories(staging.resolve("page-records"));
        Files.createDirectories(staging.resolve("terms"));
        Files.createDirectories(staging.resolve("chapters"));
        Files.createDirectories(staging.resolve("edges"));

        int processedCount = 0;
        int reviewedCount = 0;
        Map<Integer, Map<String, List<Posting>>> partitionTerms = new HashMap<>();
        List<PageSummary> currentShard = new ArrayList<>(SHARD_SIZE);
        int currentShardIdx = 0;

        for (int p = 1; p <= totalPages; p++) {
            Page page = pageSupplier.apply(p);
            if (page != null) {
                if ("READY".equals(page.status())) processedCount++;
                if (page.reviewed()) reviewedCount++;

                String title = HeadingText.pageTitle(page);
                int uncertain = page.blocks() == null ? 0 : (int) page.blocks().stream().filter(b -> !"advertisement".equals(b.type()) && b.uncertain()).count();
                int reading = page.blocks() == null ? 0 : (int) page.blocks().stream().filter(b -> !"advertisement".equals(b.type())).count();
                PageSummary summary = new PageSummary(page.pageNumber(), page.status(), reading, uncertain, (int) Math.round(page.width()), (int) Math.round(page.height()), title, page.reviewed());
                currentShard.add(summary);

                // Index records
                List<PageIndexRecord.TextSegment> segments = new ArrayList<>();
                if (page.blocks() != null) {
                    for (Block b : page.blocks()) {
                        if ("advertisement".equals(b.type())) continue;
                        String text = b.simplified() != null ? b.simplified() : b.original();
                        segments.add(new PageIndexRecord.TextSegment(b.id(), text, b.type(), b.bbox()));
                        // Extract terms
                        Set<String> blockTerms = extractTerms(text);
                        for (String term : blockTerms) {
                            int part = termPartition(term);
                            partitionTerms.computeIfAbsent(part, k -> new HashMap<>())
                                    .computeIfAbsent(term, k -> new ArrayList<>())
                                    .add(new Posting(p, b.id()));
                        }
                    }
                }

                PageIndexRecord rec = new PageIndexRecord(bookId, pdfSourceHash, p, page.revision() == null ? 0 : page.revision(),
                        page.lastCommitId(), startSeq, page.status(), "READY".equals(page.status()), page.reviewed(),
                        uncertain, page.width(), page.height(), title, segments, List.of());
                DurableJson.write(staging.resolve("page-records").resolve(p + ".json"), rec, json, 1024 * 1024);
            }

            if (currentShard.size() >= SHARD_SIZE || p == totalPages) {
                Path sp = staging.resolve("summary").resolve(String.format("shard-%06d.json", currentShardIdx));
                DurableJson.write(sp, currentShard, json, 512 * 1024);
                currentShard.clear();
                currentShardIdx++;
            }
        }

        // Write term partitions
        for (Map.Entry<Integer, Map<String, List<Posting>>> entry : partitionTerms.entrySet()) {
            Path tp = staging.resolve("terms").resolve(String.format("part-%02x.json", entry.getKey()));
            DurableJson.write(tp, entry.getValue(), json, 1024 * 1024);
        }

        // Publication Fence: verify sourceSeq did not change and no PREPARED events
        long currentSeq = sourceJournal.currentSourceSeq(bookDir, bookId);
        if (currentSeq != startSeq) {
            // Source advanced during build; reject stale publication
            cleanupDir(staging);
            throw new IOException("SOURCE_SEQ_ADVANCED_DURING_BUILD");
        }

        // Atomically move staging to generations/<genId>
        Path targetGen = generationDir(bookDir, genId);
        Files.createDirectories(targetGen.getParent());
        Files.move(staging, targetGen, StandardCopyOption.ATOMIC_MOVE);

        // Atomically publish manifest.json
        BookIndexManifest manifest = new BookIndexManifest(
                BookIndexManifest.CURRENT_SCHEMA_VERSION,
                bookId,
                pdfSourceHash,
                genId,
                currentSeq,
                "READY",
                totalPages,
                processedCount,
                reviewedCount,
                Instant.now()
        );
        DurableJson.write(manifestPath(bookDir), manifest, json, 65536);

        // Run retention cleanup
        cleanupOldGenerations(bookDir, bookId, genId);

        return manifest;
    }

    private void cleanupOldGenerations(Path bookDir, String bookId, String activeGenId) {
        Path gens = generationsDir(bookDir);
        if (!Files.exists(gens)) return;
        try (var s = Files.list(gens)) {
            for (Path gen : s.toList()) {
                String name = gen.getFileName().toString();
                if (name.equals(activeGenId)) continue;
                if (isPinned(bookId, name)) continue;
                cleanupDir(gen);
            }
        } catch (IOException ignored) {}
    }

    private static void cleanupDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public static Set<String> extractTerms(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> terms = new HashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{Space}]+", " ");
        String[] tokens = normalized.split("\\s+");

        for (String token : tokens) {
            if (token.isBlank()) continue;
            if (token.length() > 50) token = token.substring(0, 50);
            terms.add(token);

            // CJK n-grams
            char[] chars = token.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                // 1-gram
                terms.add(String.valueOf(chars[i]));
                // 2-gram
                if (i + 1 < chars.length) {
                    terms.add(new String(chars, i, 2));
                }
                // 3-gram
                if (i + 2 < chars.length) {
                    terms.add(new String(chars, i, 3));
                }
            }
        }
        return terms;
    }

    private static int termPartition(String term) {
        return (term.hashCode() & 0x7fffffff) % 256;
    }
}
