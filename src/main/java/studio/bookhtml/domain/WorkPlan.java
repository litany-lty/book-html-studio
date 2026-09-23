package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import studio.bookhtml.decision.DecisionHash;

import java.util.*;

/**
 * G09 / B08: 完整 V3 固定计划模型 (WorkPlan)。
 * 两层冻结模型：
 * 1. 父计划 (parentPlanHash)：覆盖整页任务所有阶段与固定权重；
 * 2. 子计划 (reviewPlanHash)：覆盖局部核对 (REVIEW) 等切片阶段；
 * 绑定上下文快照哈希 (contextHash) 与持久事件序号 (eventSeq)；
 * 严格分离完成率 (weightedPercent) 与核对准确率 (accuracyRatio)；
 * 冻结分母不变量 (Frozen Denominator Invariant)：分母冻结后不可变更，完成单位不可超额。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkPlan(
        String planId,
        String bookId,
        int pageNumber,
        int pageRevision,
        long eventSeq,
        String contextHash,
        String parentPlanHash,
        String reviewPlanHash,
        Map<String, StagePlan> stages,
        ReviewSubPlan reviewSubPlan,
        String currentStage,
        String lifecycle,
        boolean frozen
) {
    public static final int WEIGHT_OCR = 30;
    public static final int WEIGHT_STRUCTURE = 20;
    public static final int WEIGHT_REVIEW = 35;
    public static final int WEIGHT_VALIDATING = 10;
    public static final int WEIGHT_PUBLISHING = 5;

    public static final Set<String> ALLOWED_OUTCOMES = Set.of(
            "SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED", "DEFERRED"
    );

    public static final Set<String> TERMINAL_LIFECYCLES = Set.of(
            "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED", "UNKNOWN"
    );

    public record StagePlan(
            String name,
            int weight,
            String unitKind,
            int totalUnits,
            int succeeded,
            int failed,
            int skipped,
            int cancelled,
            Set<String> completedUnitIds,
            boolean inProgress,
            boolean completed
    ) {
        public StagePlan {
            completedUnitIds = completedUnitIds == null ? Set.of() : Set.copyOf(completedUnitIds);
            if (totalUnits < 0 || totalUnits > 4096)
                throw new IllegalArgumentException("invalid stage plan size: " + totalUnits);
        }

        public int completedUnits() {
            return completedUnitIds.size();
        }

        public double accuracyRatio() {
            int comp = completedUnits();
            return comp == 0 ? 1.0 : (double) succeeded / comp;
        }

        public double successCoverageRatio() {
            return totalUnits == 0 ? 1.0 : (double) succeeded / totalUnits;
        }

        public StagePlan withUnits(int newTotalUnits, String newUnitKind) {
            if (totalUnits != 0 && totalUnits != newTotalUnits && !completedUnitIds.isEmpty()) {
                throw new IllegalStateException("completed plan denominator is frozen");
            }
            return new StagePlan(name, weight, newUnitKind, newTotalUnits, succeeded, failed, skipped, cancelled,
                    completedUnitIds, inProgress, completed);
        }

        public StagePlan recordUnit(String unitId, String outcome) {
            if (completedUnitIds.contains(unitId)) return this;
            if (!ALLOWED_OUTCOMES.contains(outcome))
                throw new IllegalArgumentException("invalid unit outcome: " + outcome);
            if (completedUnits() >= totalUnits)
                throw new IllegalStateException("unit exceeds frozen plan");
            Set<String> nextUnits = new HashSet<>(completedUnitIds);
            nextUnits.add(unitId);
            int nextSucceeded = succeeded + ("SUCCEEDED".equals(outcome) ? 1 : 0);
            int nextFailed = failed + ("FAILED".equals(outcome) ? 1 : 0);
            int nextCancelled = cancelled + ("CANCELLED".equals(outcome) ? 1 : 0);
            int nextSkipped = skipped + (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(outcome) ? 1 : 0);
            boolean done = nextUnits.size() >= totalUnits;
            return new StagePlan(name, weight, unitKind, totalUnits, nextSucceeded, nextFailed, nextSkipped, nextCancelled,
                    Set.copyOf(nextUnits), !done, done);
        }

        public StagePlan start() {
            return new StagePlan(name, weight, unitKind, totalUnits, succeeded, failed, skipped, cancelled,
                    completedUnitIds, true, false);
        }

        public StagePlan complete() {
            return new StagePlan(name, weight, unitKind, totalUnits, succeeded, failed, skipped, cancelled,
                    completedUnitIds, false, true);
        }
    }

    public record ReviewSubPlan(
            String subPlanId,
            int totalUnits,
            List<String> unitIds,
            String reviewPlanHash
    ) {
        public ReviewSubPlan {
            unitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
            if (totalUnits < 0 || totalUnits > 4096)
                throw new IllegalArgumentException("invalid review sub-plan size: " + totalUnits);
        }
    }

    public WorkPlan {
        stages = stages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(stages));
        lifecycle = lifecycle == null ? "RUNNING" : lifecycle;
        currentStage = currentStage == null ? "PREPARING" : currentStage;
    }

    public static WorkPlan createDefault(String bookId, int pageNumber, int pageRevision, long eventSeq, String contextHash) {
        String planId = UUID.randomUUID().toString();
        Map<String, StagePlan> defaultStages = new LinkedHashMap<>();
        defaultStages.put("OCR", new StagePlan("OCR", WEIGHT_OCR, "PAGE", 1, 0, 0, 0, 0, Set.of(), false, false));
        defaultStages.put("STRUCTURE", new StagePlan("STRUCTURE", WEIGHT_STRUCTURE, "PAGE", 1, 0, 0, 0, 0, Set.of(), false, false));
        defaultStages.put("REVIEW", new StagePlan("REVIEW", WEIGHT_REVIEW, "CHUNK", 0, 0, 0, 0, 0, Set.of(), false, false));
        defaultStages.put("VALIDATING", new StagePlan("VALIDATING", WEIGHT_VALIDATING, "PAGE", 1, 0, 0, 0, 0, Set.of(), false, false));
        defaultStages.put("PUBLISHING", new StagePlan("PUBLISHING", WEIGHT_PUBLISHING, "PAGE", 1, 0, 0, 0, 0, Set.of(), false, false));

        String parentHash = computeParentPlanHash(bookId, pageNumber, pageRevision, eventSeq, contextHash, defaultStages);

        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq,
                contextHash == null ? "" : contextHash,
                parentHash, null, defaultStages, null,
                "PREPARING", "RUNNING", false
        );
    }

    public WorkPlan freezeReviewSubPlan(int totalUnits, List<String> unitIds) {
        if (this.frozen && reviewSubPlan != null) {
            if (reviewSubPlan.totalUnits() != totalUnits || (unitIds != null && !unitIds.equals(reviewSubPlan.unitIds()))) {
                throw new IllegalStateException("WorkPlan denominator is frozen");
            }
            return this;
        }

        List<String> finalUnitIds = unitIds == null ? List.of() : List.copyOf(unitIds);
        String reviewHash = computeReviewPlanHash(bookId, pageNumber, eventSeq, contextHash, parentPlanHash, totalUnits, finalUnitIds);
        ReviewSubPlan subPlan = new ReviewSubPlan(UUID.randomUUID().toString(), totalUnits, finalUnitIds, reviewHash);

        Map<String, StagePlan> nextStages = new LinkedHashMap<>(stages);
        StagePlan reviewStage = nextStages.get("REVIEW");
        if (reviewStage != null) {
            nextStages.put("REVIEW", reviewStage.withUnits(totalUnits, "CHUNK"));
        }

        String nextParentHash = computeParentPlanHash(bookId, pageNumber, pageRevision, eventSeq, contextHash, nextStages);

        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq, contextHash,
                nextParentHash, reviewHash, nextStages, subPlan, currentStage, lifecycle, true
        );
    }

    public WorkPlan withPlanHashes(String parentHash, String reviewHash) {
        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq, contextHash,
                parentHash != null ? parentHash : parentPlanHash,
                reviewHash != null ? reviewHash : reviewPlanHash,
                stages, reviewSubPlan, currentStage, lifecycle, frozen
        );
    }

    public WorkPlan transitionStage(String nextStage) {
        Objects.requireNonNull(nextStage, "nextStage 不能为空");
        if (Objects.equals(currentStage, nextStage)) return this;

        Map<String, StagePlan> nextStages = new LinkedHashMap<>(stages);
        StagePlan prev = nextStages.get(currentStage);
        if (prev != null && prev.inProgress()) {
            nextStages.put(currentStage, prev.complete());
        }

        StagePlan target = nextStages.get(nextStage);
        if (target != null) {
            nextStages.put(nextStage, target.start());
        }

        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq, contextHash,
                parentPlanHash, reviewPlanHash, nextStages, reviewSubPlan, nextStage, lifecycle, frozen
        );
    }

    public WorkPlan recordUnitDone(String stageName, String unitId, String outcome) {
        Objects.requireNonNull(stageName, "stageName 不能为空");
        Objects.requireNonNull(unitId, "unitId 不能为空");
        Objects.requireNonNull(outcome, "outcome 不能为空");

        Map<String, StagePlan> nextStages = new LinkedHashMap<>(stages);
        StagePlan stage = nextStages.get(stageName);
        if (stage == null) {
            // Stage not defined; ignore or create default stage
            return this;
        }

        StagePlan updated = stage.recordUnit(unitId, outcome);
        nextStages.put(stageName, updated);

        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq, contextHash,
                parentPlanHash, reviewPlanHash, nextStages, reviewSubPlan, currentStage, lifecycle, frozen
        );
    }

    public WorkPlan finish(String terminalLifecycle) {
        if (!TERMINAL_LIFECYCLES.contains(terminalLifecycle)) {
            throw new IllegalArgumentException("invalid terminal lifecycle: " + terminalLifecycle);
        }

        Map<String, StagePlan> nextStages = new LinkedHashMap<>(stages);
        if ("SUCCEEDED".equals(terminalLifecycle)) {
            for (Map.Entry<String, StagePlan> entry : nextStages.entrySet()) {
                if (!entry.getValue().completed()) {
                    nextStages.put(entry.getKey(), entry.getValue().complete());
                }
            }
        }

        return new WorkPlan(
                planId, bookId, pageNumber, pageRevision, eventSeq, contextHash,
                parentPlanHash, reviewPlanHash, nextStages, reviewSubPlan, currentStage,
                terminalLifecycle, frozen
        );
    }

    @JsonProperty("weightedPercent")
    public int weightedPercent() {
        if ("SUCCEEDED".equals(lifecycle)) return 100;
        int sum = 0;
        for (StagePlan sp : stages.values()) {
            if (sp.completed()) {
                sum += sp.weight();
            } else if (sp.inProgress()) {
                if (sp.totalUnits() > 0) {
                    sum += (int) ((long) sp.weight() * sp.completedUnits() / sp.totalUnits());
                }
            }
        }
        return Math.min(99, sum);
    }

    @JsonProperty("accuracyRatio")
    public double accuracyRatio() {
        int totalSucceeded = 0;
        int totalCompleted = 0;
        for (StagePlan sp : stages.values()) {
            totalSucceeded += sp.succeeded();
            totalCompleted += sp.completedUnits();
        }
        return totalCompleted == 0 ? 1.0 : (double) totalSucceeded / totalCompleted;
    }

    @JsonProperty("successCoverageRatio")
    public double successCoverageRatio() {
        int totalSucceeded = 0;
        int totalUnits = 0;
        for (StagePlan sp : stages.values()) {
            totalSucceeded += sp.succeeded();
            totalUnits += sp.totalUnits();
        }
        return totalUnits == 0 ? 1.0 : (double) totalSucceeded / totalUnits;
    }

    public int totalEndedUnits() {
        int count = 0;
        for (StagePlan sp : stages.values()) {
            count += sp.completedUnits();
        }
        return count;
    }

    public int totalUnits() {
        int count = 0;
        for (StagePlan sp : stages.values()) {
            count += sp.totalUnits();
        }
        return count;
    }

    public static String computeParentPlanHash(
            String bookId,
            int pageNumber,
            int pageRevision,
            long eventSeq,
            String contextHash,
            Map<String, StagePlan> stageMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bookId", bookId);
        map.put("pageNumber", pageNumber);
        map.put("pageRevision", pageRevision);
        map.put("eventSeq", eventSeq);
        map.put("contextHash", contextHash != null ? contextHash : "");
        List<Map<String, Object>> stageList = new ArrayList<>();
        if (stageMap != null) {
            for (StagePlan sp : stageMap.values()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("name", sp.name());
                sm.put("weight", sp.weight());
                sm.put("totalUnits", sp.totalUnits());
                sm.put("unitKind", sp.unitKind());
                stageList.add(sm);
            }
        }
        map.put("stages", stageList);
        return DecisionHash.of(map);
    }

    public static String computeReviewPlanHash(
            String bookId,
            int pageNumber,
            long eventSeq,
            String contextHash,
            String parentPlanHash,
            int totalUnits,
            List<String> unitIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bookId", bookId);
        map.put("pageNumber", pageNumber);
        map.put("eventSeq", eventSeq);
        map.put("contextHash", contextHash != null ? contextHash : "");
        map.put("parentPlanHash", parentPlanHash != null ? parentPlanHash : "");
        map.put("totalUnits", totalUnits);
        map.put("unitIds", unitIds != null ? unitIds : List.of());
        return DecisionHash.of(map);
    }
}
