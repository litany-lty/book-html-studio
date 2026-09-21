package studio.bookhtml.decision;

import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Service;
import studio.bookhtml.config.DecisionProperties;

/**
 * JR-04：统一外发门。决策任务接受外发计划前检查模式、数据授权、供应商 allowlist、
 * 对应 key/model、预算与期限；worker 调用每个 provider 前重新检查当前有效授权，
 * 撤销后不得继续发送。“用户请求新增视觉证据”不等于“服务器允许向此供应商外发”。
 */
@Service
public class DecisionOutboundGate {
    static final Set<String> ALLOWLIST = Set.of("TYPESAFE", "MOCK", "QWEN-CROP");

    public enum Purpose { VISION, JEV }

    /** 调用方声明的当前能力（key/模型/通道配置），由 coordinator/collector 实时组装。 */
    public record CapabilityView(boolean qwenConfigured, String jevApiKey, String jevModel) {}

    public record GateRequest(Purpose purpose, String provider, long deadlineNanosRemaining,
                              CapabilityView capabilities) {}

    public record GateVerdict(boolean allowed, String reasonCode) {
        static GateVerdict allow() {
            return new GateVerdict(true, null);
        }

        static GateVerdict deny(String reasonCode) {
            return new GateVerdict(false, reasonCode);
        }
    }

    private final DecisionProperties config;

    public DecisionOutboundGate(DecisionProperties config) {
        this.config = config;
    }

    public GateVerdict check(GateRequest request) {
        if (request == null || request.provider() == null)
            return GateVerdict.deny("GATE_INVALID");
        // OFF 不得由 JEV 路径派生任何外呼（含 Qwen 裁图）
        String mode = config.getMode();
        if (!"SHADOW".equalsIgnoreCase(mode) && !"ASSIST".equalsIgnoreCase(mode))
            return GateVerdict.deny("DECISION_OFF");
        if (!config.isAllowCloudData()) return GateVerdict.deny("DATA_EGRESS_NOT_AUTHORIZED");
        if (!ALLOWLIST.contains(request.provider()))
            return GateVerdict.deny("PROVIDER_NOT_ALLOWLISTED");
        if (request.deadlineNanosRemaining() <= 0) return GateVerdict.deny("DEADLINE_EXHAUSTED");
        return switch (request.provider()) {
            case "MOCK" -> GateVerdict.allow();
            case "TYPESAFE" -> {
                if (request.capabilities() == null || request.capabilities().jevApiKey() == null
                        || request.capabilities().jevApiKey().isBlank())
                    yield GateVerdict.deny("MISSING_API_KEY");
                if (request.capabilities().jevModel() == null
                        || request.capabilities().jevModel().isBlank())
                    yield GateVerdict.deny("MISSING_MODEL");
                yield GateVerdict.allow();
            }
            case "QWEN-CROP" -> {
                if (request.capabilities() == null || !request.capabilities().qwenConfigured())
                    yield GateVerdict.deny("VISION_CHANNEL_NOT_CONFIGURED");
                yield GateVerdict.allow();
            }
            default -> GateVerdict.deny("PROVIDER_NOT_ALLOWLISTED");
        };
    }

    /** 预算探针（只读建议；原子执行靠 reserve）。 */
    public String budgetProbe(String bookId, DecisionBudget budget, long amountMinor, Long limitMinor) {
        if (limitMinor == null || limitMinor < 0) return "BUDGET_NOT_SET";
        try {
            long[] totals = budget.totals(bookId);
            long active;
            try {
                active = Math.addExact(totals[0], totals[1]);
            } catch (ArithmeticException overflow) {
                return "BUDGET_REJECTED";
            }
            try {
                if (Math.addExact(active, amountMinor) > limitMinor) return "BUDGET_REJECTED";
            } catch (ArithmeticException overflow) {
                return "BUDGET_REJECTED";
            }
            return null;
        } catch (IOException e) {
            return "BUDGET_UNAVAILABLE";
        }
    }
}
