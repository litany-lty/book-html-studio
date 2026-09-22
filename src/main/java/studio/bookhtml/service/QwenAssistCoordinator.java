package studio.bookhtml.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/**
 * U5：Qwen 增强协调。分派、收集终态、确定性合并（按计划顺序，不按返回先后）；
 * 不在子任务里直接写 Page；所有正式输出走一次最终资格检查（调用方提交时）。
 *
 * <p>失败组保留原文；全局结构无法验证时，成功的局部核对也不靠错误顺序强行发布。
 * 源页面变更由调用方 CAS 保证，本版不做跨版本自动三方合并。
 */
@Service
public class QwenAssistCoordinator {
    private QwenRequestGate gate;
    private QwenTextReviewClient reviewClient;
    private QwenLayoutClient structureClient;
    private TraditionalConverter converter;
    private ProcessingProgressService progress;
    @Autowired(required = false)
    public void setProgress(ProcessingProgressService progress) { this.progress = progress; }

    private volatile ExecutorService pool;

    @Autowired(required = false)
    public void setRequestGate(QwenRequestGate gate) {
        this.gate = gate;
    }

    @Autowired(required = false)
    public void setReviewClient(QwenTextReviewClient reviewClient) {
        this.reviewClient = reviewClient;
    }

    @Autowired(required = false)
    public void setStructureClient(QwenLayoutClient structureClient) {
        this.structureClient = structureClient;
    }

    @Autowired(required = false)
    public void setConverter(TraditionalConverter converter) {
        this.converter = converter;
    }

    private synchronized ExecutorService pool() {
        if (pool == null || pool.isShutdown()) {
            // U5：独立有界出站池；禁止把付费模型请求投到无界 common pool。
            // 页级编排等待结果，但不等出站池自身的任务（无同池 join 死锁）。
            int size = gate == null ? 3 : Math.max(1, gate.maxConcurrent());
            pool = new java.util.concurrent.ThreadPoolExecutor(size, size, 0L,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(24), runnable -> {
                Thread thread = new Thread(runnable, "qwen-assist-chunk");
                thread.setDaemon(true);
                return thread;
            }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        }
        return pool;
    }

    @PreDestroy
    public synchronized void close() {
        if (pool != null) pool.shutdownNow();
        pool = null;
    }

    public record CoordinateResult(List<Block> blocks,
                                   String actualProvider,
                                   List<String> warnings,
                                   int succeededChunks,
                                   int failedChunks,
                                   int deferredChunks) {}

    /**
     * @param regionImages 每组一张必要高清区域图（chunkId → PNG 字节；全页归一化坐标裁剪，见 8.5）
     * @param overviewImage 可选小概览（可为 null）
     * @param foreground 当前阅读页优先（后台队列让槽，不强杀）
     */
    public CoordinateResult coordinate(String bookId, int pageNumber,
                                       List<Block> baselineBlocks,
                                       Map<String, String> parentTexts,
                                       QwenTaskPlanner.PlannedReview plan,
                                       Map<String, byte[]> regionImages,
                                       byte[] overviewImage,
                                       String layout,
                                       boolean foreground,
                                       BooleanSupplier cancelled) throws Exception {
        if (reviewClient == null || structureClient == null)
            throw new OcrException("Qwen 分组增强未装配（缺核对/结构客户端）");
        List<String> warnings = new ArrayList<>();
        QwenRequestGate.Budget budget = gate == null
                ? new QwenRequestGate.Budget(8) : gate.newBudget();

        // 1. 轻量全局结构：只定区域/读序/角色建议，不逐字重抄。
        List<String> baselineOrder = baselineBlocks.stream()
                .filter(b -> b != null && b.id() != null)
                .map(Block::id).toList();
        List<String> confirmedOrder = new ArrayList<>(baselineOrder);
        if (!plan.chunks().isEmpty() && structureClient.configured()) {
            if (budget.reserve(1)) {
                try (UsageContext.Scope ignored =
                             UsageContext.open(bookId, pageNumber, "QWEN_STRUCTURE:" + pageNumber)) {
                    List<String> proposed = structureClient.structurePlan(
                            regionImages.get("__overview__"), baselineBlocks, layout, cancelled, foreground);
                    if (isPermutation(baselineOrder, proposed)) {
                        confirmedOrder = proposed;
                    } else {
                        warnings.add("全局结构顺序校验失败（循环/缺失/多余 ID），已回退到已验证几何顺序");
                    }
                } catch (CancelledException e) {
                    throw e;
                } catch (Exception e) {
                    warnings.add("全局结构请求失败，已回退到已验证几何顺序，不阻塞局部核对");
                }
            } else {
                warnings.add("调用预算不足，跳过全局结构请求，使用已验证几何顺序");
            }
        }

        // 2. 局部核对并发（有界池），按计划顺序收集。
        Map<String, Block> byId = new HashMap<>();
        for (Block block : baselineBlocks) {
            if (block != null && block.id() != null) byId.put(block.id(), block);
        }
        List<IndexedOutcome> outcomes = new ArrayList<>();
        if (!plan.chunks().isEmpty()) {
            var snapshot = progress == null ? null : progress.latest(bookId, pageNumber);
            java.util.UUID attemptId = snapshot == null ? null : snapshot.attemptId();
            if (attemptId != null) {
                progress.stage(bookId, pageNumber, attemptId, "REVIEW");
                progress.plan(bookId, pageNumber, attemptId, "TEXT_GROUPS", plan.chunks().size());
            }
            List<CompletableFuture<IndexedOutcome>> futures = new ArrayList<>();
            for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) {
                CompletableFuture<IndexedOutcome> future;
                java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
                try {
                    future = CompletableFuture.supplyAsync(() -> {
                        started.set(true);
                        if (attemptId != null) progress.inFlight(bookId, pageNumber, attemptId, 1);
                        try { return runChunk(bookId, pageNumber, chunk, parentTexts, regionImages.get(chunk.chunkId()),
                                overviewImage, foreground, budget, cancelled); }
                        finally { /* Completion accounting happens for success, failure and cancellation alike. */ }
                    }, pool());
                } catch (java.util.concurrent.RejectedExecutionException full) {
                    future = CompletableFuture.completedFuture(new IndexedOutcome(chunk.plannedOrder(), chunk,
                            null, "局部核对队列已满，保留原文"));
                }
                futures.add(future.whenComplete((outcome, error) -> {
                    if (attemptId != null) progress.unitDone(bookId, pageNumber, attemptId,
                            error == null && outcome != null && outcome.result() != null, started.get());
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    outcomes.add(futures.get(i).join());
                } catch (Exception e) {
                    QwenTaskPlanner.ChunkTask chunk = plan.chunks().get(i);
                    outcomes.add(new IndexedOutcome(chunk.plannedOrder(), chunk, null,
                            "本组执行异常，已保留原文"));
                }
            }
            outcomes.sort(Comparator.comparingInt(IndexedOutcome::order));
        }

        // 3. 确定性合并：结构修改与文字疑点分开；普通核对不改写 original。
        Map<String, Block> merged = new HashMap<>(byId);
        int succeeded = 0, failed = 0;
        for (IndexedOutcome outcome : outcomes) {
            if (outcome.result() == null) {
                failed++;
                if (outcome.note() != null) warnings.add(outcome.note());
                continue;
            }
            succeeded++;
            if (outcome.result().dropped() > 0) {
                warnings.add(outcome.chunk().chunkId() + " 丢弃 " + outcome.result().dropped()
                        + " 条非法发现，已保留原文");
            }
            for (QwenTextReviewClient.ChunkFinding finding : outcome.result().findings()) {
                Block parent = merged.get(finding.sourceId());
                if (parent == null) continue;
                ContentIssue issue = toIssue(outcome.chunk(), finding, parent);
                if (issue == null) continue;
                List<ContentIssue> issues = new ArrayList<>(
                        parent.issues() == null ? List.of() : parent.issues());
                boolean duplicate = issues.stream().anyMatch(existing ->
                        existing != null && issue.id().equals(existing.id()));
                if (duplicate) continue;
                issues.add(issue);
                issues.sort(Comparator.comparingInt(ContentIssue::start)
                        .thenComparingInt(ContentIssue::end));
                merged.put(parent.id(), new Block(parent.id(), parent.type(), parent.order(),
                        parent.bbox(), parent.writingMode(), parent.original(), parent.simplified(),
                        parent.confidence(), true, parent.reviewed(), parent.headingLevel(),
                        parent.source(), parent.sourceIds(), parent.suggestion(),
                        parent.sourceRect(), List.copyOf(issues)));
            }
        }
        if (!plan.deferred().isEmpty()) {
            warnings.add(plan.deferred().size() + " 组超出调用预算，保留原文未核对，明确部分增强");
        }

        // 4. 按确认顺序重排（order 字段），块身份与来源不变。
        Map<String, Integer> orderIndex = new HashMap<>();
        for (int i = 0; i < confirmedOrder.size(); i++) {
            orderIndex.putIfAbsent(confirmedOrder.get(i), i);
        }
        List<Block> ordered = new ArrayList<>(merged.values());
        ordered.sort(Comparator.comparingInt((Block b) ->
                        orderIndex.getOrDefault(b.id(), Integer.MAX_VALUE))
                .thenComparingInt(Block::order));
        List<Block> renumbered = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Block block = ordered.get(i);
            renumbered.add(new Block(block.id(), block.type(), i, block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), block.uncertain(),
                    block.reviewed(), block.headingLevel(), block.source(), block.sourceIds(),
                    block.suggestion(), block.sourceRect(), block.issues()));
        }
        return new CoordinateResult(List.copyOf(renumbered), "+qwen-chunked-review",
                List.copyOf(warnings), succeeded, failed, plan.deferred().size());
    }

    private IndexedOutcome runChunk(String bookId, int pageNumber, QwenTaskPlanner.ChunkTask chunk,
                                    Map<String, String> parentTexts,
                                    byte[] regionImage, byte[] overviewImage,
                                    boolean foreground, QwenRequestGate.Budget budget,
                                    BooleanSupplier cancelled) {
        return runChunkOn(bookId, pageNumber, chunk, parentTexts, regionImage, overviewImage,
                foreground, budget, cancelled, 0);
    }

    private IndexedOutcome runChunkOn(String bookId, int pageNumber, QwenTaskPlanner.ChunkTask chunk,
                                      Map<String, String> parentTexts,
                                      byte[] regionImage, byte[] overviewImage,
                                      boolean foreground, QwenRequestGate.Budget budget,
                                      BooleanSupplier cancelled, int regroupDepth) {
        // 子任务显式携带 book/page/task；进入工作线程打开 scope，finally 关闭。
        UsageContext.Scope scope = UsageContext.open(bookId, pageNumber, "QWEN_TEXT_REVIEW:" + chunk.chunkId());
        try {
            QwenTextReviewClient.ReviewResult result = reviewClient.reviewChunk(chunk, parentTexts,
                    regionImage, overviewImage, foreground, budget, cancelled);
            return new IndexedOutcome(chunk.plannedOrder(), chunk, result, null);
        } catch (QwenTextReviewClient.TruncatedException truncated) {
            // 输出截断且预算允许：拆更小组重试一次。
            if (regroupDepth >= 1 || budget.remaining() < 2) {
                return new IndexedOutcome(chunk.plannedOrder(), chunk, null,
                        chunk.chunkId() + " 输出截断且无法再分，保留原文");
            }
            List<QwenTaskPlanner.ChunkTask> halves = splitChunk(chunk);
            if (halves.size() < 2) {
                return new IndexedOutcome(chunk.plannedOrder(), chunk, null,
                        chunk.chunkId() + " 输出截断且无法再分，保留原文");
            }
            List<QwenTextReviewClient.ChunkFinding> merged = new ArrayList<>();
            int dropped = 0;
            for (QwenTaskPlanner.ChunkTask half : halves) {
                IndexedOutcome sub = runChunkOn(bookId, pageNumber, half, parentTexts, regionImage,
                        overviewImage, foreground, budget, cancelled, regroupDepth + 1);
                if (sub.result() == null) {
                    return new IndexedOutcome(chunk.plannedOrder(), chunk, null,
                            chunk.chunkId() + " 再分组后仍失败，保留原文");
                }
                merged.addAll(sub.result().findings());
                dropped += sub.result().dropped();
            }
            return new IndexedOutcome(chunk.plannedOrder(), chunk,
                    new QwenTextReviewClient.ReviewResult(chunk.chunkId(), merged, dropped), null);
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            return new IndexedOutcome(chunk.plannedOrder(), chunk, null,
                    chunk.chunkId() + " 核对失败，已保留原文");
        } finally {
            scope.close();
        }
    }

    private static List<QwenTaskPlanner.ChunkTask> splitChunk(QwenTaskPlanner.ChunkTask chunk) {
        List<QwenTaskPlanner.OwnedRange> owned = chunk.ownedRanges();
        if (owned.size() < 2 && owned.stream().allMatch(r -> r.end() - r.start() < 200)) {
            return List.of();
        }
        if (owned.size() >= 2) {
            int mid = owned.size() / 2;
            return List.of(
                    new QwenTaskPlanner.ChunkTask(chunk.chunkId() + "a", chunk.kind(),
                            owned.subList(0, mid), chunk.contextRanges(), chunk.plannedOrder(),
                            chunk.promptVersion(), chunk.policyVersion()),
                    new QwenTaskPlanner.ChunkTask(chunk.chunkId() + "b", chunk.kind(),
                            owned.subList(mid, owned.size()), chunk.contextRanges(),
                            chunk.plannedOrder(), chunk.promptVersion(), chunk.policyVersion()));
        }
        QwenTaskPlanner.OwnedRange single = owned.get(0);
        int mid = single.start() + (single.end() - single.start()) / 2;
        return List.of(
                new QwenTaskPlanner.ChunkTask(chunk.chunkId() + "a", chunk.kind(),
                        List.of(new QwenTaskPlanner.OwnedRange(single.sourceId(), single.start(), mid)),
                        chunk.contextRanges(), chunk.plannedOrder(), chunk.promptVersion(), chunk.policyVersion()),
                new QwenTaskPlanner.ChunkTask(chunk.chunkId() + "b", chunk.kind(),
                        List.of(new QwenTaskPlanner.OwnedRange(single.sourceId(), mid, single.end())),
                        chunk.contextRanges(), chunk.plannedOrder(), chunk.promptVersion(), chunk.policyVersion()));
    }

    private ContentIssue toIssue(QwenTaskPlanner.ChunkTask chunk,
                                 QwenTextReviewClient.ChunkFinding finding, Block parent) {
        try {
            String original = parent.original() == null ? "" : parent.original();
            // 回解父块偏移：同一父块多区间无交叠，quote 逐字相等定位所属区间。
            int base = -1;
            for (QwenTaskPlanner.OwnedRange owned : chunk.ownedRanges()) {
                if (!owned.sourceId().equals(finding.sourceId())) continue;
                if (finding.start() < 0 || finding.end() > owned.end() - owned.start()) continue;
                int candidateBase = owned.start();
                int candidateStart = candidateBase + finding.start();
                int candidateEnd = candidateBase + finding.end();
                if (candidateEnd > original.length()) continue;
                if (!original.substring(candidateStart, candidateEnd).equals(finding.quote())) continue;
                base = candidateBase;
                break;
            }
            if (base < 0) return null;
            int start = base + finding.start();
            int end = base + finding.end();
            if (start < 0 || end > original.length() || end <= start) return null;
            String simplified = parent.simplified() == null ? "" : parent.simplified();
            int simpleStart = start;
            int simpleEnd = end;
            if (converter != null && !simplified.isEmpty()) {
                try {
                    simpleStart = converter.toSimplified(original.substring(0, start)).length();
                    simpleEnd = converter.toSimplified(original.substring(0, end)).length();
                } catch (Exception ignored) {
                    simpleStart = Math.min(start, simplified.length());
                    simpleEnd = Math.min(end, simplified.length());
                }
            }
            String reason = finding.reason() == null ? "" : finding.reason();
            if (reason.length() > 1000) return null;
            String candidate = finding.candidateText();
            if (candidate != null && candidate.length() > 1000) return null;
            String id = chunk.chunkId() + "/" + finding.sourceId() + ":" + start + "-" + end;
            if (id.length() > 120) return null;
            return new ContentIssue(id, finding.kind(), start, end, simpleStart, simpleEnd,
                    reason, false, null, candidate, null);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPermutation(List<String> expected, List<String> actual) {
        if (expected == null || actual == null || expected.size() != actual.size()) return false;
        return new java.util.HashSet<>(expected).equals(new java.util.HashSet<>(actual));
    }

    private record IndexedOutcome(int order, QwenTaskPlanner.ChunkTask chunk,
                                  QwenTextReviewClient.ReviewResult result, String note) {}
}
