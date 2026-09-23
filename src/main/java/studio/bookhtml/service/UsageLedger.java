package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-book append-only physical-call ledger. Only one file for an attempt is replaced atomically. */
@Service
public class UsageLedger {
    private static final int MAX_ENTRY_BYTES = 8_192;
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private final BookStore books;
    private final SettingsService settings;
    private final ObjectMapper json;
    private final Object[] locks = java.util.stream.IntStream.range(0,64).mapToObj(i -> new Object()).toArray();

    public record Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                        Integer pageNumber, String operation, String provider, String model,
                        String status, Long inputTokens, Long outputTokens, String feeKind,
                        String currency, String amount, SettingsService.Rate priceSnapshot,
                        String executionId, String taskHash, Long attemptSeq) {
        public Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                     Integer pageNumber, String operation, String provider, String model,
                     String status, Long inputTokens, Long outputTokens, String feeKind,
                     String currency, String amount, SettingsService.Rate priceSnapshot,
                     String executionId, String taskHash) {
            this(id,bookId,createdAt,updatedAt,pageNumber,operation,provider,model,status,inputTokens,
                    outputTokens,feeKind,currency,amount,priceSnapshot,executionId,taskHash,null);
        }
        public Entry(String id, String bookId, Instant createdAt, Instant updatedAt,
                     Integer pageNumber, String operation, String provider, String model,
                     String status, Long inputTokens, Long outputTokens, String feeKind,
                     String currency, String amount, SettingsService.Rate priceSnapshot) {
            this(id,bookId,createdAt,updatedAt,pageNumber,operation,provider,model,status,inputTokens,
                    outputTokens,feeKind,currency,amount,priceSnapshot,null,null);
        }
    }

    public UsageLedger(BookStore books, SettingsService settings, ObjectMapper json) {
        this.books = books; this.settings = settings; this.json = json;
    }
    private Object lock(String bookId) { return locks[Math.floorMod(bookId.hashCode(),locks.length)]; }

    /** Durable SEND_UNKNOWN must precede the physical transport call. */
    public String start(String provider, String model) throws IOException {
        return record(provider,model,"SENT_UNKNOWN");
    }
    /** PREPARED records an unsent intent; the send boundary promotes it before transport. */
    public String prepare(String provider, String model) throws IOException {
        return record(provider,model,"PREPARED");
    }
    public void sending(String id) throws IOException { change(id,"SENT_UNKNOWN",null,null); }
    private String record(String provider, String model, String status) throws IOException {
        UsageContext.Value context = requireContext();
        String bookId = context.bookId();
        synchronized (lock(bookId)) {
            each(bookId, ignored -> {}); // Existing corruption blocks new sends instead of presenting an empty ledger.
            String id = UUID.randomUUID().toString();
            SettingsService.Rate rate = settings.state().billingRates().stream()
                    .filter(r -> r.provider().equals(provider) && r.model().equals(model)).findFirst().orElse(null);
            Instant now = Instant.now();
            Entry entry = new Entry(id, bookId, now, now, context.pageNumber(), context.operation(),
                    provider, model, status, null, null, "UNKNOWN", null, null, rate,
                    executionId(context), taskHash(context), attemptSeq());
            save(bookId, entry);
            return id;
        }
    }

    public void notSent(String id) throws IOException { change(id, "NOT_SENT", null, null); }
    public void unknown(String id) throws IOException { change(id, "OUTCOME_UNKNOWN", null, null); }
    public void pending(String id) throws IOException { change(id, "PENDING", null, null); }
    public void succeeded(String id) throws IOException { change(id, "SUCCEEDED", null, null); }
    public void failed(String id) throws IOException { change(id, "FAILED", null, null); }
    public void usage(String id, Long inputTokens, Long outputTokens) throws IOException {
        if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0)
            throw new IOException("invalid usage");
        change(id, null, inputTokens, outputTokens);
    }
    /** Preserve returned token usage before any business/semantic response validation. */
    public void captureUsage(String id, JsonNode root) throws IOException {
        if (root == null) return;
        JsonNode node = root.path("usage");
        if (!node.isObject()) node = root.path("data").path("usage");
        if (!node.isObject()) return;
        Long input = firstLong(node, "input_tokens", "prompt_tokens", "inputTokens");
        Long output = firstLong(node, "output_tokens", "completion_tokens", "outputTokens");
        if (input != null || output != null) usage(id, input, output);
    }
    private static Long firstLong(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0)
                return value.longValue();
        }
        return null;
    }
    public void cacheReused(String provider, String model) throws IOException {
        UsageContext.Value context = requireContext();
        synchronized (lock(context.bookId())) {
            each(context.bookId(), ignored -> {});
            Instant now = Instant.now();
            String id = UUID.randomUUID().toString();
            save(context.bookId(), new Entry(id, context.bookId(), now, now, context.pageNumber(),
                    context.operation(), provider, model, "CACHE_REUSED", null, null,
                    "CACHE_REUSE", null, null, null,executionId(context),taskHash(context),attemptSeq()));
        }
    }

    private void change(String id, String status, Long inputTokens, Long outputTokens) throws IOException {
        UsageContext.Value context = requireContext();
        synchronized (lock(context.bookId())) {
            Entry old = readEntry(context.bookId(), id);
            if (old == null || "CACHE_REUSED".equals(old.status())) throw new IOException("usage attempt absent");
            if ("SENT_UNKNOWN".equals(status) && !"PREPARED".equals(old.status()))
                throw new IOException("send intent is not prepared");
            if ("PREPARED".equals(old.status()) && status != null
                    && !Set.of("SENT_UNKNOWN","NOT_SENT").contains(status))
                throw new IOException("prepared request has not been sent");
            if(status != null && Set.of("SUCCEEDED","FAILED","NOT_SENT").contains(old.status())
                    && !old.status().equals(status)) throw new IOException("usage terminal outcome already recorded");
            if("NOT_SENT".equals(old.status()) && (inputTokens != null || outputTokens != null))
                throw new IOException("unsent request cannot have token usage");
            Long input = inputTokens == null ? old.inputTokens() : inputTokens;
            Long output = outputTokens == null ? old.outputTokens() : outputTokens;
            String nextStatus = status == null ? old.status() : status;
            boolean requestPriced = old.priceSnapshot() != null && !old.priceSnapshot().perRequest().isEmpty();
            Estimate estimate = requestPriced && !"SUCCEEDED".equals(nextStatus)
                    ? null : estimate(old.priceSnapshot(), input, output);
            save(context.bookId(), new Entry(old.id(), old.bookId(), old.createdAt(), Instant.now(),
                    old.pageNumber(), old.operation(), old.provider(), old.model(), nextStatus,
                    input, output, estimate == null ? "UNKNOWN" : "ESTIMATED",
                    estimate == null ? null : estimate.currency(),
                    estimate == null ? null : estimate.amount(), old.priceSnapshot(),old.executionId(),old.taskHash(),old.attemptSeq()));
        }
    }

    private record Estimate(String currency, String amount) {}
    private static Estimate estimate(SettingsService.Rate rate, Long input, Long output) {
        if (rate == null) return null;
        if (!rate.perRequest().isEmpty()) return new Estimate(rate.currency(), amount(new BigDecimal(rate.perRequest())));
        if (input == null || output == null || rate.inputPerMillion().isEmpty() || rate.outputPerMillion().isEmpty())
            return null;
        BigDecimal total = new BigDecimal(rate.inputPerMillion()).multiply(BigDecimal.valueOf(input))
                .add(new BigDecimal(rate.outputPerMillion()).multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
        return new Estimate(rate.currency(), amount(total));
    }
    private static String amount(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    public Map<String, Object> view(String bookId, int offset, int limit) throws IOException {
        return view(bookId, offset, limit, null, null);
    }

    public Map<String, Object> view(String bookId, int offset, int limit, String asOfValue) throws IOException {
        return view(bookId, offset, limit, asOfValue, null);
    }

    public Map<String, Object> view(String bookId, int offset, int limit, String asOfValue, String cursorValue) throws IOException {
        if (offset < 0 || offset > 10_000 || limit < 1 || limit > 100)
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量分页参数无效");
        EntryPosition cursor = parseCursor(cursorValue);
        if (cursor != null && offset != 0) throw new ApiException(HttpStatus.BAD_REQUEST, "游标分页不使用 offset");
        Instant asOf;
        try { asOf = asOfValue == null || asOfValue.isBlank() ? Instant.now() : Instant.parse(asOfValue); }
        catch (DateTimeParseException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "用量截止时间无效"); }
        synchronized (lock(bookId)) {
            int retain = (cursor == null ? offset : 0) + limit;
            Comparator<Entry> order = Comparator.comparing(Entry::createdAt).thenComparing(Entry::id);
            PriorityQueue<Entry> page = new PriorityQueue<>(retain + 1, order.reversed());
            Totals all = new Totals();
            Map<String, Totals> byProvider = new LinkedHashMap<>();
            Instant[] first = {null};
            long[] count = {0}, after = {0};
            each(bookId, e -> {
                if (e.createdAt().isAfter(asOf)) return;
                count[0]++;
                if (first[0] == null || e.createdAt().isBefore(first[0])) first[0] = e.createdAt();
                all.add(e);
                byProvider.computeIfAbsent(e.provider() + "\u0000" + e.model(), ignored -> new Totals()).add(e);
                if (cursor != null && (e.createdAt().compareTo(cursor.createdAt()) < 0
                        || e.createdAt().equals(cursor.createdAt()) && e.id().compareTo(cursor.id()) <= 0)) return;
                after[0]++;
                if (page.size() < retain) page.add(e);
                else if (order.compare(e, page.peek()) < 0) { page.poll(); page.add(e); }
            });
            List<Entry> selected = new ArrayList<>(page);
            selected.sort(order);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bookId", bookId);
            result.put("recordedSince", first[0] == null ? null : first[0].toString());
            result.put("asOf", asOf.toString());
            result.put("historicalCoverage", "SINCE_TRACKING_ONLY");
            result.put("totals", all.view());
            List<Map<String, Object>> providers = new ArrayList<>();
            byProvider.forEach((key, values) -> {
                int separator = key.indexOf('\u0000');
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("provider", key.substring(0, separator)); row.put("model", key.substring(separator + 1));
                row.putAll(values.view()); providers.add(row);
            });
            result.put("providers", providers);
            int from = Math.min(cursor == null ? offset : 0, selected.size());
            List<Entry> slice = selected.subList(from, selected.size());
            result.put("entries", slice.stream().map(UsageLedger::entryView).toList());
            boolean more = after[0] > retain;
            result.put("nextOffset", cursor == null && more && offset + limit <= 10_000 ? offset + limit : null);
            result.put("nextCursor", more && !slice.isEmpty() ? cursor(slice.get(slice.size() - 1)) : null);
            return result;
        }
    }

    private record EntryPosition(Instant createdAt, String id) {}
    private static EntryPosition parseCursor(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 160 || !value.matches("[A-Za-z0-9_-]+"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "用量游标无效");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII);
            int split = decoded.indexOf('|');
            if (split < 1 || split != decoded.lastIndexOf('|')) throw new IllegalArgumentException();
            String id = decoded.substring(split + 1);
            if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
            return new EntryPosition(Instant.parse(decoded.substring(0, split)), id);
        } catch (Exception e) { throw new ApiException(HttpStatus.BAD_REQUEST, "用量游标无效"); }
    }
    private static String cursor(Entry e) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (e.createdAt().toString() + "|" + e.id()).getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> entryView(Entry e) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id()); out.put("createdAt", e.createdAt()); out.put("updatedAt", e.updatedAt());
        out.put("pageNumber", e.pageNumber()); out.put("operation", e.operation());
        out.put("executionId", e.executionId()); out.put("taskHash", e.taskHash()); out.put("attemptSeq",e.attemptSeq());
        out.put("provider", e.provider()); out.put("model", e.model()); out.put("status", e.status());
        out.put("inputTokens", e.inputTokens()); out.put("outputTokens", e.outputTokens());
        out.put("feeKind", e.feeKind()); out.put("currency", e.currency()); out.put("amount", e.amount());
        return out;
    }
    private static final class Totals {
        long requests, success, failed, pending, prepared, cacheHits, notSent, unpriced, unknownInput, unknownOutput;
        BigInteger input = BigInteger.ZERO, output = BigInteger.ZERO;
        final Map<String, BigDecimal> estimates = new LinkedHashMap<>();
        void add(Entry e) {
            if ("CACHE_REUSED".equals(e.status())) { cacheHits++; return; }
            if ("NOT_SENT".equals(e.status())) { notSent++; return; }
            if ("PREPARED".equals(e.status())) { prepared++; return; }
            requests++;
            switch (e.status()) { case "SUCCEEDED" -> success++; case "FAILED" -> failed++; default -> pending++; }
            if (e.inputTokens() == null) unknownInput++; else input = input.add(BigInteger.valueOf(e.inputTokens()));
            if (e.outputTokens() == null) unknownOutput++; else output = output.add(BigInteger.valueOf(e.outputTokens()));
            if ("ESTIMATED".equals(e.feeKind())) estimates.merge(e.currency(), new BigDecimal(e.amount()), BigDecimal::add);
            else unpriced++;
        }
        Map<String, Object> view() {
            List<Map<String, String>> amounts = new ArrayList<>();
            estimates.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> amounts.add(Map.of("currency", e.getKey(), "amount", amount(e.getValue()))));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("requests", requests); result.put("success", success); result.put("failed", failed);
            result.put("pending", pending); result.put("cacheHits", cacheHits); result.put("notSent", notSent); result.put("prepared", prepared);
            result.put("inputTokens", input); result.put("outputTokens", output);
            result.put("unknownInputTokenRequests", unknownInput); result.put("unknownOutputTokenRequests", unknownOutput);
            result.put("unpricedRequests", unpriced);
            result.put("reportedAmounts", List.of()); result.put("estimatedAmounts", amounts);
            return result;
        }
    }

    private interface EntryConsumer { void accept(Entry entry) throws IOException; }
    private void each(String bookId, EntryConsumer consumer) throws IOException {
        Path dir = usageDir(bookId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("usage ledger unsafe");
        try (var paths = Files.list(dir)) {
            for (Iterator<Path> it = paths.iterator(); it.hasNext();) {
                consumer.accept(readPath(bookId, it.next()));
            }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("usage ledger damaged"); }
    }
    private Entry readEntry(String bookId, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IOException("invalid attempt id");
        Path path = usageDir(bookId).resolve(id + ".json");
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? readPath(bookId, path) : null;
    }
    private Entry readPath(String bookId, Path path) throws IOException {
        if (!path.getFileName().toString().matches("[0-9a-f-]{36}\\.json")
                || Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > MAX_ENTRY_BYTES) throw new IOException("usage ledger damaged");
        Entry e;
        try (var parser=json.getFactory().createParser(Files.readAllBytes(path))) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode tree = json.readTree(parser);
            if (tree==null || !tree.isObject() || parser.nextToken()!=null) throw new IOException("trailing usage record");
            for (String field:List.of("pageNumber","inputTokens","outputTokens","attemptSeq")) {
                JsonNode value=tree.get(field);
                if (value!=null && !value.isNull() && (!value.isIntegralNumber() || !value.canConvertToLong()
                        || field.equals("pageNumber") && !value.canConvertToInt()))
                    throw new IOException("invalid usage integer");
            }
            e = json.treeToValue(tree,Entry.class);
        }
        catch (Exception ex) { throw new IOException("usage ledger damaged"); }
        e = normalizeLegacyOperation(e);
        validateEntry(bookId, path.getFileName().toString(), e);
        return e;
    }
    /** Earlier coordinators wrote a task suffix into the operation enum. Adapt only
     * these generated forms; arbitrary corrupt operations still fail closed.
     */
    private static Entry normalizeLegacyOperation(Entry e) {
        if(e==null || e.operation()==null || e.executionId()!=null || e.taskHash()!=null || e.attemptSeq()!=null) return e;
        String operation=e.operation(), task;
        if(operation.startsWith("QWEN_STRUCTURE:")) {
            task=operation.substring("QWEN_STRUCTURE:".length());
            if(e.pageNumber()==null || !task.equals(String.valueOf(e.pageNumber()))) return e;
            operation="QWEN_STRUCTURE";
        } else if(operation.startsWith("QWEN_TEXT_REVIEW:")) {
            task=operation.substring("QWEN_TEXT_REVIEW:".length());
            if(!task.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) return e;
            operation="QWEN_TEXT_REVIEW";
        } else return e;
        if(!"qwen".equals(e.provider())) return e;
        return new Entry(e.id(),e.bookId(),e.createdAt(),e.updatedAt(),e.pageNumber(),operation,
                e.provider(),e.model(),e.status(),e.inputTokens(),e.outputTokens(),e.feeKind(),
                e.currency(),e.amount(),e.priceSnapshot(),null,studio.bookhtml.decision.DecisionHash.sha256Hex(task));
    }
    private static void validateEntry(String bookId, String filename, Entry e) throws IOException {
        if (e == null || !bookId.equals(e.bookId()) || !filename.equals(e.id() + ".json")
                || e.createdAt() == null || e.updatedAt() == null || e.createdAt().isAfter(e.updatedAt())
                || e.provider() == null || e.model() == null || e.operation() == null
                || !e.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
                || !e.operation().matches("[A-Z][A-Z0-9_]{0,39}")
                || e.pageNumber() != null && e.pageNumber() < 1
                || !Set.of("paddle-aistudio", "ppocr", "qwen", "jev").contains(e.provider())
                || !Set.of("PREPARED", "SENT_UNKNOWN", "PENDING", "OUTCOME_UNKNOWN", "NOT_SENT", "SUCCEEDED", "FAILED", "CACHE_REUSED").contains(e.status())
                || !Set.of("UNKNOWN", "ESTIMATED", "CACHE_REUSE").contains(e.feeKind())
                || e.inputTokens() != null && e.inputTokens() < 0
                || e.outputTokens() != null && e.outputTokens() < 0
                || ("ESTIMATED".equals(e.feeKind()) != (e.currency() != null && e.amount() != null))
                || e.currency() != null && !Set.of("CNY", "USD").contains(e.currency())
                || e.amount() != null && !validAmount(e.amount())
                || e.executionId() != null && !canonicalUuid(e.executionId())
                || e.attemptSeq() != null && (e.attemptSeq()<1 || e.executionId()==null)
                || e.taskHash() != null && !e.taskHash().matches("[0-9a-f]{64}")
                || e.priceSnapshot() != null && !validPriceSnapshot(e)
                || ("CACHE_REUSED".equals(e.status()) != "CACHE_REUSE".equals(e.feeKind())))
            throw new IOException("usage ledger damaged");
    }
    private static boolean validAmount(String value) {
        try { return value.length() <= 48 && value.matches("[0-9]+(?:\\.[0-9]{1,12})?")
                && new BigDecimal(value).signum() >= 0; }
        catch (NumberFormatException ex) { return false; }
    }
    private static boolean validPriceSnapshot(Entry e) {
        SettingsService.Rate r = e.priceSnapshot();
        return e.provider().equals(r.provider()) && e.model().equals(r.model())
                && r.currency() != null && Set.of("CNY", "USD").contains(r.currency())
                && r.perRequest() != null && r.inputPerMillion() != null && r.outputPerMillion() != null
                && (r.perRequest().isEmpty() || validAmount(r.perRequest()))
                && (r.inputPerMillion().isEmpty() || validAmount(r.inputPerMillion()))
                && (r.outputPerMillion().isEmpty() || validAmount(r.outputPerMillion()))
                && (Set.of("paddle-aistudio", "ppocr").contains(e.provider())
                ? r.inputPerMillion().isEmpty() && r.outputPerMillion().isEmpty()
                : r.perRequest().isEmpty());
    }
    private Path usageDir(String bookId) { return books.bookDir(bookId).resolve("usage"); }
    private void save(String bookId, Entry entry) throws IOException {
        validateEntry(bookId,entry.id() + ".json",entry);
        Path dir = usageDir(bookId), temp = null;
        try {
            if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir);
            if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("usage ledger unsafe");
            setPermissions(dir, DIR_PERMS);
            Path target = dir.resolve(entry.id() + ".json");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) throw new IOException("usage ledger unsafe");
            temp = Files.createTempFile(dir, "entry-", ".tmp");
            setPermissions(temp, FILE_PERMS);
            byte[] bytes = json.writeValueAsBytes(entry);
            if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("usage entry too large");
            boolean interrupted = Thread.interrupted();
            try (var file = java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while(buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            } finally { if(interrupted) Thread.currentThread().interrupt(); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(dir);
        } finally { if (temp != null) Files.deleteIfExists(temp); }
    }
    private static final java.util.concurrent.atomic.AtomicBoolean FORCE_WARNING = new java.util.concurrent.atomic.AtomicBoolean();
    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) throws IOException {
        if (Files.getFileAttributeView(path,java.nio.file.attribute.PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)
            Files.setPosixFilePermissions(path,permissions);
    }
    private static void forceDirectory(Path directory) {
        boolean interrupted=Thread.interrupted();
        try (var channel=java.nio.channels.FileChannel.open(directory,StandardOpenOption.READ)) { channel.force(true); }
        catch (IOException | UnsupportedOperationException unavailable) {
            if(FORCE_WARNING.compareAndSet(false,true)) System.getLogger(UsageLedger.class.getName()).log(
                    System.Logger.Level.WARNING,"Usage ledger directory fsync unavailable; power-loss durability is filesystem dependent");
        } finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
    private static String executionId(UsageContext.Value context) throws IOException {
        var scope=QwenExecutionScope.current();
        if(scope==null) return null;
        if(!scope.bookId().equals(context.bookId()) || !java.util.Objects.equals(scope.pageNumber(),context.pageNumber()))
            throw new IOException("mismatched page execution scope");
        return scope.executionId().toString();
    }
    private static boolean canonicalUuid(String value) {
        try { return UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static Long attemptSeq() {
        var scope=QwenExecutionScope.current();
        return scope==null || scope.attemptSeq()<1 ? null : scope.attemptSeq();
    }
    private static String taskHash(UsageContext.Value context) {
        if(context.taskId()==null) return null;
        return studio.bookhtml.decision.DecisionHash.sha256Hex(context.taskId());
    }
    private static UsageContext.Value requireContext() throws IOException {
        UsageContext.Value context = UsageContext.current();
        if (context == null || context.bookId() == null || context.operation() == null
                || !context.operation().matches("[A-Z][A-Z0-9_]{0,39}")
                || context.pageNumber() != null && context.pageNumber() < 1
                || context.taskId() != null && context.taskId().length() > 256)
            throw new IOException("usage scope missing");
        return context;
    }
}
