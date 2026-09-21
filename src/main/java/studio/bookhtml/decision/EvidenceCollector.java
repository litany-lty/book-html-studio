package studio.bookhtml.decision;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import org.springframework.stereotype.Service;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.IssueImageService;
import studio.bookhtml.service.QwenOcrClient;

/**
 * J03：通道无关的局部证据获取。native/Paddle/Qwen/local 的已有疑点进入同一受控入口；
 * 零新增外呼证据（当前转录、既有推测）优先；确需新增视觉调用时一次一议、有预算才行；
 * 无原图或对齐失败输出可理解原因，不伪造调用记录，不使用未授权渠道。
 */
@Service
public class EvidenceCollector {
    static final long MAX_CROP_BYTES = 4L * 1024 * 1024;
    static final long RESERVE_PER_VISION_CALL_MINOR = 1;

    private final CandidateResolutionService resolution;
    private final IssueImageService images;
    private final QwenOcrClient qwen;
    private final DecisionBudget budget;
    private final DecisionProperties config;

    public EvidenceCollector(CandidateResolutionService resolution, IssueImageService images,
                             QwenOcrClient qwen, DecisionBudget budget, DecisionProperties config) {
        this.resolution = resolution;
        this.images = images;
        this.qwen = qwen;
        this.budget = budget;
        this.config = config;
    }

    public record Collection(List<CandidateResolutionService.RawCandidate> raws,
                             List<DecisionModels.Candidate> legacy,
                             int freshVisionAttempts, List<String> reasons) {
        public Collection {
            raws = raws == null ? List.of() : List.copyOf(raws);
            legacy = legacy == null ? List.of() : List.copyOf(legacy);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /**
     * @param allowFreshVision 初次点击默认 false（复用已有证据）；true 仍须过配置/授权/额度/次数
     * @param allowSecondPath 显式授权才允许第二次不同路径获取（默认最多一次新增视觉请求）
     * @param freshCalls 本 issue 已用新增视觉次数（调用方持有，跨候选生成前去重靠 admissionKey）
     */
    public Collection collect(DecisionModels.IssueRef ref, String frozenOriginal, Block block,
                              ContentIssue issue, String bookId, Page page,
                              boolean allowFreshVision, boolean allowSecondPath,
                              AtomicInteger freshCalls, BooleanSupplier cancelled) {
        List<CandidateResolutionService.RawCandidate> raws = new ArrayList<>();
        List<DecisionModels.Candidate> legacy = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        int attempts = 0;
        String target = frozenOriginal.substring(ref.startUtf16(), ref.endUtf16());
        if (!target.isBlank()) {
            raws.add(new CandidateResolutionService.RawCandidate(target,
                    producerKind(block == null ? null : block.source()),
                    block == null ? "unknown" : String.valueOf(block.source()),
                    null, null, "run-current", "G-current", List.of(), null,
                    DecisionModels.LocatorMode.REGION,
                    block == null || block.bbox() == null ? null : block.bbox().clone(),
                    "ocr-direct", List.of(), block == null ? null : block.confidence(),
                    ref.startUtf16(), ref.endUtf16()));
        } else {
            reasons.add("CURRENT_BLANK");
        }
        // 既有推测走 LEGACY 兼容导入，不反向转繁体，不伪造图像支持
        if (issue != null && issue.inferredText() != null && !issue.inferredText().isBlank()) {
            try {
                legacy.add(resolution.legacyCandidate(ref, issue.inferredText(),
                        "cand-legacy-" + Math.abs(issue.id().hashCode())));
            } catch (IllegalArgumentException e) {
                reasons.add("LEGACY_INVALID");
            }
        }
        if (!allowFreshVision) {
            reasons.add("FRESH_VISION_NOT_REQUESTED");
            return new Collection(raws, legacy, attempts, reasons);
        }
        int cap = allowSecondPath ? 2 : 1;
        if (config.getMaxFreshVisionCallsPerIssue() < cap) cap = config.getMaxFreshVisionCallsPerIssue();
        if (freshCalls.get() >= cap) {
            reasons.add("VISION_CALL_CAP_REACHED");
            return new Collection(raws, legacy, attempts, reasons);
        }
        if (cancelled.getAsBoolean()) {
            reasons.add("CANCELLED_BEFORE_VISION");
            return new Collection(raws, legacy, attempts, reasons);
        }
        if (!qwen.configured()) {
            reasons.add("VISION_CHANNEL_NOT_CONFIGURED");
            return new Collection(raws, legacy, attempts, reasons);
        }
        boolean sent = false;
        boolean reserved = false;
        try {
            IssueImageService.Snippet snippet = images.locateOne(bookId, page,
                    issue == null ? null : issue.id());
            if (snippet == null || snippet.png() == null || snippet.png().length == 0) {
                reasons.add("EVIDENCE_UNAVAILABLE");
                return new Collection(raws, legacy, attempts, reasons);
            }
            if (snippet.png().length > MAX_CROP_BYTES) {
                reasons.add("PIXEL_BUDGET_EXCEEDED");
                return new Collection(raws, legacy, attempts, reasons);
            }
            if (cancelled.getAsBoolean()) {
                reasons.add("CANCELLED_BEFORE_VISION");
                return new Collection(raws, legacy, attempts, reasons);
            }
            // 预留发生在真正外呼前一刻；本地证据定位本身不计费
            Long limit = config.getMonetaryBudgetMinor();
            if (!budget.tryReserve(bookId, RESERVE_PER_VISION_CALL_MINOR, limit)) {
                reasons.add("VISION_BUDGET_REJECTED");
                return new Collection(raws, legacy, attempts, reasons);
            }
            reserved = true;
            String layout = (block != null && "vertical-rl".equals(block.writingMode())) ? "vertical" : "horizontal";
            int width = 0, height = 0;
            try {
                var image = ImageIO.read(new ByteArrayInputStream(snippet.png()));
                if (image != null) { width = image.getWidth(); height = image.getHeight(); }
            } catch (Exception ignored) {
            }
            sent = true;
            attempts++;
            freshCalls.incrementAndGet();
            List<studio.bookhtml.domain.Block> reread =
                    qwen.recognize(snippet.png(), width, height, layout, cancelled);
            String text = reread.stream().map(b -> b.original() == null ? "" : b.original())
                    .reduce("", String::concat).strip();
            if (text.isEmpty()) {
                reasons.add("VISION_EMPTY");
                return new Collection(raws, legacy, attempts, reasons);
            }
            raws.add(new CandidateResolutionService.RawCandidate(text,
                    DecisionModels.SourceKind.CROP_OCR, "qwen-crop-ocr", null, null,
                    "run-crop-" + freshCalls.get(), "G-crop-" + freshCalls.get(), List.of(),
                    cropHash(snippet.png()),
                    "glyphs".equals(snippet.mode()) ? DecisionModels.LocatorMode.GLYPH
                            : DecisionModels.LocatorMode.REGION,
                    snippet.bbox() == null ? null : snippet.bbox().clone(), "crop-v1",
                    List.of("E-crop-" + freshCalls.get()), null,
                    ref.startUtf16(), ref.endUtf16()));
            return new Collection(raws, legacy, attempts, reasons);
        } catch (Exception e) {
            // 已发送则费用未知，保留预留；未发送则释放；失败不是“原文已正确”，不写假成功
            if (sent) budget.settleUnknown(bookId);
            else if (reserved) budget.release(bookId, RESERVE_PER_VISION_CALL_MINOR);
            reasons.add(sent ? "VISION_FAILED" : "VISION_NOT_SENT");
            return new Collection(raws, legacy, attempts, reasons);
        }
    }

    static DecisionModels.SourceKind producerKind(String source) {
        if (source == null) return DecisionModels.SourceKind.PRIMARY_OCR;
        if (source.startsWith("native")) return DecisionModels.SourceKind.NATIVE_TEXT;
        if (source.startsWith("manual") || source.startsWith("human")) return DecisionModels.SourceKind.HUMAN_INPUT;
        return DecisionModels.SourceKind.PRIMARY_OCR;
    }

    private static String cropHash(byte[] png) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(png);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
