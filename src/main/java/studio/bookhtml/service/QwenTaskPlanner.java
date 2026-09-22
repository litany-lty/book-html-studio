package studio.bookhtml.service;

import org.springframework.stereotype.Component;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * U5：固定 ownedRanges 的任务规划。优先按同一段/栏/语义区域分组；
 * 诗歌分节、表格/命盘/公式/谱系保持原子区域；邻接上下文可重叠，
 * 但拥有写入权的区间不得重叠；超出预算分解或降级，不截断原文。
 */
@Component
public class QwenTaskPlanner {
    private final QwenAssistProperties config;

    public QwenTaskPlanner(QwenAssistProperties config) {
        this.config = config;
    }

    public enum Kind { STRUCTURE, TEXT_REVIEW, TOC_REGION_RECOVERY }

    /** 拥有写入权的原文区间（父块 ID + 原文 UTF-16 半开区间）。 */
    public record OwnedRange(String sourceId, int start, int end) {}

    /** 只读邻接上下文（理解用，无修改权）。 */
    public record ContextRange(String sourceId, int start, int end) {}

    public record ChunkTask(String chunkId,
                            String kind,
                            List<OwnedRange> ownedRanges,
                            List<ContextRange> contextRanges,
                            int plannedOrder,
                            String promptVersion,
                            String policyVersion) {
        public ChunkTask {
            ownedRanges = ownedRanges == null ? List.of() : List.copyOf(ownedRanges);
            contextRanges = contextRanges == null ? List.of() : List.copyOf(contextRanges);
        }
    }

    public record PlannedReview(List<ChunkTask> chunks,
                                List<ChunkTask> deferred,
                                int skippedAtomic,
                                int estimatedCalls) {}

    private static final java.util.Set<String> ATOMIC_VISUAL =
            java.util.Set.of("figure", "table", "formula");
    private static final java.util.Set<String> REVIEWABLE =
            java.util.Set.of("text", "heading", "caption");

    /**
     * 规划局部文字核对组：每组 6–12 个短块、总转录约 2500 字符起；
     * 长块走区间切片（保持父块 ID）；复杂视觉块不按块数硬切。
     */
    public PlannedReview planReview(List<Block> blocks, String promptVersion, String policyVersion) {
        int maxBlocks = Math.max(1, config.getReviewChunkBlocksMax());
        int minBlocks = Math.max(1, Math.min(config.getReviewChunkBlocksMin(), maxBlocks));
        int maxChars = Math.max(500, config.getReviewChunkChars());
        List<Block> candidates = new ArrayList<>();
        int skippedAtomic = 0;
        if (blocks != null) {
            List<Block> sorted = new ArrayList<>(blocks.stream().filter(java.util.Objects::nonNull).toList());
            sorted.sort(java.util.Comparator.comparingInt(Block::order));
            for (Block block : sorted) {
                if (block == null || block.id() == null) continue;
                if (ATOMIC_VISUAL.contains(block.type())) {
                    skippedAtomic++;
                    continue;
                }
                if (!REVIEWABLE.contains(block.type())) continue;
                String text = block.original() == null ? "" : block.original();
                if (text.isEmpty()) continue;
                candidates.add(block);
            }
        }
        List<ChunkTask> chunks = new ArrayList<>();
        List<OwnedRange> current = new ArrayList<>();
        List<String> currentIds = new ArrayList<>();
        int currentChars = 0;
        int order = 0;
        for (Block block : candidates) {
            String text = block.original();
            if (text.length() > maxChars) {
                // 长块区间切片：父块 ID + 原文区间，不制造新 OCR 来源。
                if (!current.isEmpty()) {
                    chunks.add(buildChunk(order++, current, currentIds, promptVersion, policyVersion));
                    current = new ArrayList<>();
                    currentIds = new ArrayList<>();
                    currentChars = 0;
                }
                for (int start = 0; start < text.length();) {
                    int end = Math.min(text.length(), start + maxChars);
                    end = adjustToCodePointBoundary(text, end);
                    if (end <= start) end = text.length();
                    chunks.add(buildChunk(order++, List.of(new OwnedRange(block.id(), start, end)),
                            List.of(block.id()), promptVersion, policyVersion));
                    start = end;
                }
                continue;
            }
            if ((!current.isEmpty() && current.size() >= maxBlocks)
                    || (!current.isEmpty() && currentChars + text.length() > maxChars)) {
                chunks.add(buildChunk(order++, current, currentIds, promptVersion, policyVersion));
                current = new ArrayList<>();
                currentIds = new ArrayList<>();
                currentChars = 0;
            }
            current.add(new OwnedRange(block.id(), 0, text.length()));
            currentIds.add(block.id());
            currentChars += text.length();
        }
        if (!current.isEmpty() && (current.size() >= minBlocks || chunks.isEmpty())) {
            chunks.add(buildChunk(order++, current, currentIds, promptVersion, policyVersion));
        } else if (!current.isEmpty()) {
            // 尾组不足最小块数：并入上一组（保持语义区域完整，不硬切）。
            ChunkTask last = chunks.remove(chunks.size() - 1);
            List<OwnedRange> merged = new ArrayList<>(last.ownedRanges());
            merged.addAll(current);
            chunks.add(new ChunkTask(last.chunkId(), last.kind(), merged,
                    last.contextRanges(), last.plannedOrder(), promptVersion, policyVersion));
        }
        // 邻接上下文：前后组边界块各 1 个只读重叠（理解用）。
        List<ChunkTask> withContext = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkTask chunk = chunks.get(i);
            List<ContextRange> context = new ArrayList<>();
            if (i > 0) {
                List<OwnedRange> prev = chunks.get(i - 1).ownedRanges();
                OwnedRange edge = prev.get(prev.size() - 1);
                context.add(new ContextRange(edge.sourceId(), edge.start(), edge.end()));
            }
            if (i + 1 < chunks.size()) {
                OwnedRange edge = chunks.get(i + 1).ownedRanges().get(0);
                context.add(new ContextRange(edge.sourceId(), edge.start(), edge.end()));
            }
            // 上下文若与本组 owned 重叠则剔除（写入权区间不得重叠）。
            java.util.Set<String> ownedKeys = new java.util.HashSet<>();
            for (OwnedRange owned : chunk.ownedRanges()) {
                ownedKeys.add(owned.sourceId() + ":" + owned.start() + ":" + owned.end());
            }
            List<ContextRange> filtered = new ArrayList<>();
            for (ContextRange ctx : context) {
                if (!ownedKeys.contains(ctx.sourceId() + ":" + ctx.start() + ":" + ctx.end())) {
                    filtered.add(ctx);
                }
            }
            withContext.add(new ChunkTask(chunk.chunkId(), chunk.kind(), chunk.ownedRanges(),
                    filtered, chunk.plannedOrder(), chunk.promptVersion(), chunk.policyVersion()));
        }
        // 预算：1 结构 + N 局部 + 预留 1 重试位；超预算的组 deferred（保留原文）。
        int budget = Math.max(1, config.getMaxPhysicalCallsPerPageAttempt());
        List<ChunkTask> deferred = new ArrayList<>();
        List<ChunkTask> executable = new ArrayList<>(withContext);
        while (executable.size() + 2 > budget && executable.size() > 1) {
            deferred.add(0, executable.remove(executable.size() - 1));
        }
        return new PlannedReview(List.copyOf(executable), List.copyOf(deferred), skippedAtomic,
                executable.size() + 1);
    }

    private static ChunkTask buildChunk(int order, List<OwnedRange> owned, List<String> ids,
                                        String promptVersion, String policyVersion) {
        return new ChunkTask("review-" + String.format("%02d", order),
                Kind.TEXT_REVIEW.name(), List.copyOf(owned), List.of(),
                order, promptVersion, policyVersion);
    }

    /** 边界不落在代理对中间。 */
    static int adjustToCodePointBoundary(String text, int end) {
        if (end <= 0 || end >= text.length()) return end;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) {
            return end + 1 <= text.length() ? end + 1 : end - 1;
        }
        return end;
    }

    public List<String> ownedIds(ChunkTask chunk) {
        List<String> ids = new ArrayList<>();
        for (OwnedRange owned : chunk.ownedRanges()) {
            if (!ids.contains(owned.sourceId())) ids.add(owned.sourceId());
        }
        return Collections.unmodifiableList(ids);
    }
}
