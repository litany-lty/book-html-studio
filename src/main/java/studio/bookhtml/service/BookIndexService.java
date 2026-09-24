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
public class BookIndexService implements AutoCloseable {
    public static final int SHARD_SIZE = 128;
    public static final int MAX_POSTING_CANDIDATES = 500;
    private static final int MAX_ACTIVE_SNAPSHOTS = 16;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(5);

    private final ObjectMapper json;
    private final SourceChangeJournal sourceReader;
    private final ExecutorService builders=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"book-index-builder");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,String> buildInputs=new HashMap<>();
    private final Map<String,Long> warmupTimes=new LinkedHashMap<>();
    private volatile boolean closed;
    private record VerifiedCatalog(BookIndexManifest manifest,Map<String,String> hashes) {}
    private final Map<String,VerifiedCatalog> catalogs=new LinkedHashMap<>(4,.75f,true);
    private static final int MAX_PAGES=100000;
    private final Map<String, CompletableFuture<BookIndexManifest>> inFlightBuilds = new ConcurrentHashMap<>();
    private final Map<String, SearchSnapshot> searchSnapshots = new ConcurrentHashMap<>();
    private static final Map<String,Map<String,Integer>> pinnedGenerations=new HashMap<>();

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
        this.json = Objects.requireNonNull(json, "json").copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.sourceReader=new SourceChangeJournal(this.json);
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
        if(!uuid(genId)) throw new IllegalArgumentException("invalid index generation");
        return generationsDir(bookDir).resolve(genId);
    }

    public static Path stagingDir(Path bookDir, String genId) {
        if(!uuid(genId)) throw new IllegalArgumentException("invalid staging generation");
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

    private static boolean uuid(String id) {
        try { return id!=null && UUID.fromString(id).toString().equals(id); } catch(IllegalArgumentException e) { return false; }
    }
    private byte[] readBytes(Path path,int maxBytes) throws IOException {
        DurableJson.rejectLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("index file missing or unsafe");
        byte[] data;
        try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { data=input.readNBytes(maxBytes+1); }
        if(data.length>maxBytes) throw new IOException("index file too large");
        return data;
    }
    private <T> T decode(byte[] data,TypeReference<T> type) throws IOException {
        try(var parser=json.getFactory().createParser(data)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            T value=json.readValue(parser,type);
            if(parser.nextToken()!=null || value==null) throw new IOException("invalid index data");
            return value;
        }
    }
    private <T> T boundedRead(Path path,int maxBytes,TypeReference<T> type) throws IOException { return decode(readBytes(path,maxBytes),type); }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String generationHash(BookIndexManifest m,Map<String,String> files) throws IOException {
        return hash(json.writeValueAsBytes(List.of(m.schemaVersion(),m.bookId(),m.pdfSourceHash(),m.generationId(),m.sourceSeq(),
                m.status(),m.totalPages(),m.processedPages(),m.reviewedPages(),m.updatedAt().toString(),new TreeMap<>(files))));
    }
    private Map<String,String> verifiedCatalog(Path dir,BookIndexManifest m) throws IOException {
        String key=dir.toAbsolutePath().normalize()+"|"+m.generationId()+"|"+m.integrityHash();
        synchronized(catalogs) {
            var hit=catalogs.get(key);
            if(hit!=null && hit.manifest().equals(m)) return hit.hashes();
        }
        Map<String,String> files=boundedRead(generationDir(dir,m.generationId()).resolve("files.json"),2*1024*1024,new TypeReference<>(){});
        if(files.size()>MAX_PAGES+1024) throw new IOException("index catalog capacity exceeded");
        for(var entry:files.entrySet()) {
            if(!entry.getKey().matches("(?:summary/shard-[0-9]{6}|page-records/[1-9][0-9]*|terms/part-[0-9a-f]{2})\\.json")
                    || entry.getValue()==null || !entry.getValue().matches("[0-9a-f]{64}")) throw new IOException("invalid index catalog");
        }
        if(!generationHash(m,files).equals(m.integrityHash())) throw new IOException("index generation integrity mismatch");
        Map<String,String> immutable=Map.copyOf(files);
        synchronized(catalogs) {
            catalogs.put(key,new VerifiedCatalog(m,immutable));
            while(catalogs.size()>4) catalogs.remove(catalogs.keySet().iterator().next());
        }
        return immutable;
    }
    private <T> T verifiedRead(Path dir,BookIndexManifest m,Path path,int maxBytes,TypeReference<T> type) throws IOException {
        String relative=generationDir(dir,m.generationId()).relativize(path).toString().replace('\\','/');
        String expected=verifiedCatalog(dir,m).get(relative);
        if(expected==null) throw new IOException("index file not in catalog");
        byte[] data=readBytes(path,maxBytes);
        if(!hash(data).equals(expected)) throw new IOException("index file checksum mismatch");
        return decode(data,type);
    }
    public BookIndexManifest manifest(Path bookDir) {
        try {
            var m=boundedRead(manifestPath(bookDir),65536,new TypeReference<BookIndexManifest>(){});
            if(m.schemaVersion()!=BookIndexManifest.CURRENT_SCHEMA_VERSION || !uuid(m.bookId())
                    || !m.bookId().equals(bookDir.getFileName().toString()) || !uuid(m.generationId())
                    || !"READY".equals(m.status()) || m.totalPages()<1 || m.totalPages()>MAX_PAGES
                    || m.integrityHash()==null || !m.integrityHash().matches("[0-9a-f]{64}")
                    || m.sourceSeq()<0 || m.processedPages()<0 || m.processedPages()>m.totalPages()
                    || m.reviewedPages()<0 || m.reviewedPages()>m.totalPages() || m.updatedAt()==null
                    || m.pdfSourceHash()==null || m.pdfSourceHash().isBlank() || m.pdfSourceHash().length()>128) return null;
            synchronized(SourceChangeJournal.publicationMonitor(bookDir)) {
                if(sourceReader.currentSourceSeq(bookDir,m.bookId())!=m.sourceSeq() || sourceReader.hasUnresolved(bookDir,m.bookId())) return null;
            }
            DurableJson.rejectLinks(generationDir(bookDir,m.generationId()));
            verifiedCatalog(bookDir,m);
            return m;
        } catch(Exception unavailable) { return null; } // Derived data is optional, never authority.
    }
    private boolean unchanged(Path dir,BookIndexManifest before) {
        var now=manifest(dir);
        return now!=null && before.generationId().equals(now.generationId()) && before.sourceSeq()==now.sourceSeq();
    }
    private static void range(int first,int limit) {
        if(first<1 || first>MAX_PAGES || limit<1 || limit>MAX_PAGES || (long)first+limit-1>MAX_PAGES)
            throw new IllegalArgumentException("invalid index page range");
    }
    public PageSummary pageSummary(Path bookDir,int pageNumber) {
        List<PageSummary> summaries=pageSummaries(bookDir,pageNumber,1);
        return summaries.isEmpty()?null:summaries.get(0);
    }
    public List<PageSummary> pageSummaries(Path bookDir,int fromPage,int limit) {
        range(fromPage,limit);var m=manifest(bookDir);
        if(m==null || fromPage>m.totalPages()) return List.of();
        int toPage=Math.min(m.totalPages(),fromPage+limit-1);
        List<PageSummary> result=new ArrayList<>(toPage-fromPage+1);
        try {
            Path gen=generationDir(bookDir,m.generationId());
            for(int shard=(fromPage-1)/SHARD_SIZE;shard<=(toPage-1)/SHARD_SIZE;shard++) {
                List<PageSummary> entries=verifiedRead(bookDir,m,shardPath(gen,shard),512*1024,new TypeReference<>(){});
                if(entries.size()>SHARD_SIZE) return List.of();
                int expected=shard*SHARD_SIZE+1;
                for(var entry:entries) {
                    if(entry==null || entry.pageNumber()!=expected++) return List.of();
                    if(entry.pageNumber()>=fromPage && entry.pageNumber()<=toPage) result.add(entry);
                }
            }
            if(result.size()!=toPage-fromPage+1 || !unchanged(bookDir,m)) return List.of();
            return List.copyOf(result);
        } catch(Exception unavailable) { return List.of(); }
    }
    /** null means unavailable/incomplete and requires authoritative fallback; [] is a verified negative. */
    public List<Map<String,Object>> search(Path bookDir,String bookId,String query,String snapshotToken,int limit) {
        if(limit<1 || limit>MAX_POSTING_CANDIDATES) throw new IllegalArgumentException("invalid search limit");
        String q=query==null?"":query.strip();
        if(q.isEmpty()) return List.of();
        if(q.length()>200) throw new ApiException(HttpStatus.BAD_REQUEST,"搜索词过长");
        if(snapshotToken!=null && !snapshotToken.isBlank()) {
            SearchSnapshot snapshot=searchSnapshots.get(snapshotToken);
            if(snapshot==null || snapshot.expired()) { searchSnapshots.remove(snapshotToken);throw new ApiException(HttpStatus.GONE,"搜索快照已失效，请重新搜索"); }
            if(!snapshot.bookId().equals(bookId) || !snapshot.query().equals(q)) throw new ApiException(HttpStatus.FORBIDDEN,"搜索快照范围不匹配");
            return snapshot.results().stream().limit(limit).toList();
        }
        BookIndexManifest m=manifest(bookDir);
        if(m==null || !bookId.equals(m.bookId())) return null;
        String needle=q.toLowerCase(Locale.ROOT);
        String anchor=needle.substring(0,needle.offsetByCodePoints(0,Math.min(3,needle.codePointCount(0,needle.length()))));
        Path gen=generationDir(bookDir,m.generationId());
        try {
            Path partFile=termPartPath(gen,termPartition(anchor));
            // New builds write all partitions, including empty ones. Missing is corruption, not a negative answer.
            Map<String,List<Posting>> partition=verifiedRead(bookDir,m,partFile,1024*1024,new TypeReference<>(){});
            List<Posting> candidates=partition.getOrDefault(anchor,List.of());
            if(candidates.size()>MAX_POSTING_CANDIDATES) return null;
            List<Posting> ordered=new ArrayList<>(candidates);
            ordered.sort(Comparator.comparingInt(Posting::pageNumber).thenComparing(Posting::blockId));
            List<Map<String,Object>> hits=new ArrayList<>();Set<String> seen=new HashSet<>();
            Map<Integer,PageIndexRecord> records=new HashMap<>();
            for(Posting post:ordered) {
                if(post.pageNumber()<1 || post.pageNumber()>m.totalPages() || post.blockId()==null) return null;
                PageIndexRecord record=records.get(post.pageNumber());
                if(record==null) {
                    record=verifiedRead(bookDir,m,pageRecordPath(gen,post.pageNumber()),1024*1024,new TypeReference<>(){});
                    if(!bookId.equals(record.bookId()) || record.pageNumber()!=post.pageNumber()
                            || !m.pdfSourceHash().equals(record.pdfSourceHash()) || record.appliedSourceSeq()!=m.sourceSeq()
                            || record.textSegments()==null) return null;
                    records.put(post.pageNumber(),record);
                }
                for(var segment:record.textSegments()) {
                    if(!post.blockId().equals(segment.blockId()) || "advertisement".equals(segment.type())) continue;
                    boolean matches=segment.text()!=null && segment.text().toLowerCase(Locale.ROOT).contains(needle)
                            || segment.original()!=null && segment.original().toLowerCase(Locale.ROOT).contains(needle);
                    if(matches && seen.add(post.pageNumber()+":"+segment.blockId())) {
                        hits.add(Map.of("pageNumber",post.pageNumber(),"blockId",segment.blockId(),"text",
                                segment.text()==null?segment.original():segment.text()));
                        if(hits.size()==limit) break;
                    }
                }
                if(hits.size()==limit) break;
            }
            return unchanged(bookDir,m)?List.copyOf(hits):null;
        } catch(Exception unavailable) { return null; }
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

    public void pinGeneration(String bookId,String generationId) {
        synchronized(pinnedGenerations) {
            if(!uuid(bookId) || !uuid(generationId)) throw new IllegalArgumentException("invalid generation lease");
            if(!pinnedGenerations.containsKey(bookId) && pinnedGenerations.size()>=128) throw new IllegalStateException("generation lease capacity");
            var byGeneration=pinnedGenerations.computeIfAbsent(bookId,k->new HashMap<>());
            if(!byGeneration.containsKey(generationId) && byGeneration.size()>=128) throw new IllegalStateException("generation lease capacity");
            byGeneration.merge(generationId,1,Math::addExact);
        }
    }
    public void unpinGeneration(String bookId,String generationId) {
        synchronized(pinnedGenerations) {
            var byGeneration=pinnedGenerations.get(bookId);if(byGeneration==null) return;
            Integer count=byGeneration.get(generationId);if(count==null) return;
            if(count<=1) byGeneration.remove(generationId);else byGeneration.put(generationId,count-1);
            if(byGeneration.isEmpty()) pinnedGenerations.remove(bookId);
        }
    }
    public boolean isPinned(String bookId,String generationId) {
        synchronized(pinnedGenerations) {
            var byGeneration=pinnedGenerations.get(bookId);return byGeneration!=null && byGeneration.getOrDefault(generationId,0)>0;
        }
    }

    /**
     * B03-05: Coalesced background build with fence gate verification.
     */
    public CompletableFuture<BookIndexManifest> buildOrRebuild(
            Path bookDir, String bookId, String pdfSourceHash, int totalPages,
            SourceChangeJournal sourceJournal,
            java.util.function.Function<Integer, Page> pageSupplier) {

        if(totalPages<1 || totalPages>MAX_PAGES || !uuid(bookId) || pdfSourceHash==null || pdfSourceHash.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid index build input"));
        String key=bookDir.toAbsolutePath().normalize()+"|"+bookId;
        String input=pdfSourceHash+"|"+totalPages;
        synchronized(inFlightBuilds) {
            if(closed) return CompletableFuture.failedFuture(new IllegalStateException("index builder closed"));
            CompletableFuture<BookIndexManifest> existing=inFlightBuilds.get(key);
            if(existing!=null) return input.equals(buildInputs.get(key))?existing:
                    CompletableFuture.failedFuture(new IOException("index build for another source already active"));
            CompletableFuture<BookIndexManifest> future=new CompletableFuture<>();
            inFlightBuilds.put(key,future);buildInputs.put(key,input);
            try {
                builders.execute(()->{
                    BookIndexManifest result=null;Throwable failure=null;
                    try { result=executeBuild(bookDir,bookId,pdfSourceHash,totalPages,sourceJournal,pageSupplier); }
                    catch(Throwable error) { failure=error; }
                    finally {
                        synchronized(inFlightBuilds) { inFlightBuilds.remove(key,future);buildInputs.remove(key); }
                    }
                    if(failure==null) future.complete(result);else future.completeExceptionally(failure);
                });
            } catch(RejectedExecutionException full) { inFlightBuilds.remove(key,future);buildInputs.remove(key);future.completeExceptionally(full); }
            return future;
        }
    }

    private BookIndexManifest executeBuild(
            Path bookDir, String bookId, String pdfSourceHash, int totalPages,
            SourceChangeJournal sourceJournal,
            java.util.function.Function<Integer, Page> pageSupplier) throws IOException {

        long startSeq;
        synchronized(SourceChangeJournal.publicationMonitor(bookDir)) {
            startSeq=sourceJournal.currentSourceSeq(bookDir,bookId);
            if(sourceJournal.hasUnresolved(bookDir,bookId)) throw new IOException("SOURCE_PUBLICATION_UNRESOLVED");
        }
        String genId = UUID.randomUUID().toString();
        Path staging = stagingDir(bookDir, genId);

        DurableJson.rejectLinks(staging);
        try {
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
        long textBudget=0,postingCount=0;

        for (int p = 1; p <= totalPages; p++) {
            if(closed || Thread.currentThread().isInterrupted()) throw new IOException("index build stopped");
            Page page = pageSupplier.apply(p);
            if(page==null || page.pageNumber()!=p) throw new IOException("INDEX_SOURCE_PAGE_MISSING_OR_MISMATCHED");
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
                        segments.add(new PageIndexRecord.TextSegment(b.id(), text, b.type(), b.bbox(),b.original()));
                        // Extract terms
                        textBudget+=(long)(text==null?0:text.length())+(b.original()==null?0:b.original().length());
                        if(textBudget>16L*1024*1024) throw new IOException("INDEX_TEXT_BUDGET_EXCEEDED");
                        Set<String> blockTerms = new HashSet<>(extractTerms(text));
                        blockTerms.addAll(extractTerms(b.original()));
                        postingCount+=blockTerms.size();
                        if(postingCount>500000) throw new IOException("INDEX_POSTING_BUDGET_EXCEEDED");
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
        for(int partition=0;partition<256;partition++) {
            DurableJson.write(termPartPath(staging,partition),partitionTerms.getOrDefault(partition,Map.of()),json,1024*1024);
        }

        Map<String,String> fileHashes=new TreeMap<>();
        try(var paths=Files.walk(staging)) {
            for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())
                fileHashes.put(staging.relativize(path).toString().replace('\\','/'),studio.bookhtml.store.DurableEventJournal.sha256Hex(path));
        }
        DurableJson.write(staging.resolve("files.json"),fileHashes,json,2*1024*1024);

        // Publication Fence: verify sourceSeq did not change and no PREPARED events
        BookIndexManifest manifest;
        if(closed || Thread.currentThread().isInterrupted()) throw new IOException("index build stopped");
        synchronized(SourceChangeJournal.publicationMonitor(bookDir)) {
        long currentSeq = sourceJournal.currentSourceSeq(bookDir, bookId);
        if (currentSeq != startSeq || sourceJournal.hasUnresolved(bookDir,bookId)) {
            // Source advanced during build; reject stale publication
            cleanupDir(staging);
            throw new IOException("SOURCE_SEQ_ADVANCED_DURING_BUILD");
        }

        // Atomically move staging to generations/<genId>
        Path targetGen = generationDir(bookDir, genId);
        DurableJson.rejectLinks(targetGen);
        Files.createDirectories(targetGen.getParent());
        Files.move(staging, targetGen, StandardCopyOption.ATOMIC_MOVE);

        // Atomically publish manifest.json
        manifest = new BookIndexManifest(
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
        manifest=manifest.withIntegrityHash(generationHash(manifest,fileHashes));
        DurableJson.write(manifestPath(bookDir), manifest, json, 65536);
        } // Source admissions cannot race the final fence and manifest replacement.

        // Run retention cleanup
        cleanupOldGenerations(bookDir, bookId, genId);

        return manifest;
        } finally { cleanupDir(staging); }
    }

    /** Schedule derived maintenance after a cache miss; no cloud call and no synchronous PDF scan. */
    public void warm(studio.bookhtml.store.BookStore store,Book book) {
        if(closed || !Files.isRegularFile(store.pdf(book.id()),LinkOption.NOFOLLOW_LINKS)) return;
        String key=store.bookDir(book.id()).toAbsolutePath().normalize()+"|"+book.id();
        synchronized(warmupTimes) {
            long now=System.nanoTime();Long before=warmupTimes.get(key);
            if(before!=null && now-before<TimeUnit.SECONDS.toNanos(30)) return;
            warmupTimes.put(key,now);
            while(warmupTimes.size()>128) warmupTimes.remove(warmupTimes.keySet().iterator().next());
        }
        // The coordinator only hashes the immutable original and enqueues the actual build;
        // it never waits for a task on its own single-worker executor.
        try { builders.execute(()->{
            if(closed || manifest(store.bookDir(book.id()))!=null) return;
            try {
                String hash=studio.bookhtml.store.DurableEventJournal.sha256Hex(store.pdf(book.id()));
                buildOrRebuild(store.bookDir(book.id()),book.id(),hash,book.totalPages(),store.sourceJournal(),
                        page->store.readPage(book.id(),page)).exceptionally(error->null);
            } catch(Exception unavailable) { /* Local reading remains available; next explicit read can retry after backoff. */ }
        }); } catch(RejectedExecutionException full) { /* Optional bounded maintenance is deferred. */ }
    }
    @Override @jakarta.annotation.PreDestroy public void close() {
        closed=true;builders.shutdownNow();
        boolean interrupted=Thread.interrupted();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!builders.isTerminated()) {
                long left=deadline-System.nanoTime();
                if(left<=0) throw new IllegalStateException("index worker did not drain");
                try { builders.awaitTermination(left,TimeUnit.NANOSECONDS); }
                catch(InterruptedException e) { interrupted=true; }
            }
            synchronized(inFlightBuilds) {
                for(var future:inFlightBuilds.values()) future.completeExceptionally(new CancellationException("index builder closed"));
                inFlightBuilds.clear();buildInputs.clear();
            }
        } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }

    private void cleanupOldGenerations(Path bookDir,String bookId,String builtGeneration) {
        List<Path> retired=new ArrayList<>();
        // Another store handle may publish after this build. Rename retired generations
        // under the publication/pin fence; delete their potentially large trees outside it.
        synchronized(SourceChangeJournal.publicationMonitor(bookDir)) {
            synchronized(pinnedGenerations) {
                try {
                    DurableJson.rejectLinks(generationsDir(bookDir));
                    BookIndexManifest current=boundedRead(manifestPath(bookDir),65536,new TypeReference<>(){});
                    if(!bookId.equals(current.bookId()) || !uuid(current.generationId())) return;
                    try(var paths=Files.list(generationsDir(bookDir))) {
                        for(Path gen:paths.toList()) {
                            String name=gen.getFileName().toString();
                            if(!uuid(name) || Files.isSymbolicLink(gen) || name.equals(current.generationId())
                                    || name.equals(builtGeneration) || isPinned(bookId,name)) continue;
                            Path trash=stagingDir(bookDir,UUID.randomUUID().toString());
                            DurableJson.rejectLinks(trash);Files.createDirectories(trash.getParent());
                            Files.move(gen,trash,StandardCopyOption.ATOMIC_MOVE);
                            retired.add(trash);
                        }
                    }
                } catch(IOException unavailable) { /* Retention failure must not undo a published generation. */ }
            }
        }
        retired.forEach(BookIndexService::cleanupDir);
    }

    private static void cleanupDir(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    public static Set<String> extractTerms(String text) {
        if(text==null || text.isBlank()) return Set.of();
        if(text.length()>256*1024) throw new IllegalArgumentException("index segment text too large");
        int[] points=text.toLowerCase(Locale.ROOT).codePoints().toArray();Set<String> terms=new HashSet<>();
        for(int i=0;i<points.length;i++) for(int length=1;length<=3 && i+length<=points.length;length++)
            terms.add(new String(points,i,length));
        return terms;
    }

    private static int termPartition(String term) {
        return (term.hashCode() & 0x7fffffff) % 256;
    }
}
