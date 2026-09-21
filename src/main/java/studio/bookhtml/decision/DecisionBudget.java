package studio.bookhtml.decision;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import studio.bookhtml.decision.DecisionStore.AttemptLedger;
import studio.bookhtml.decision.DecisionStore.AttemptState;
import studio.bookhtml.decision.DecisionStore.BudgetUnavailableException;

/**
 * JR-05：持久化费用账本。以 physicalAttemptId 为键：
 * 预留落盘成功才可发送；SEND_INTENT 持久化放在传输边界之前；
 * 能证明未发送才释放；发送未知保留 UNKNOWN；损坏账本禁止新外呼不清零；
 * 限额判断用“已实报累计 + 未结算预留”（溢出安全）；金额/次数单位不混用。
 * 未核实真实货币定价时，minor 是本地调用核算单位，不是美元/人民币；
 * 真实费用另记（usage/pricingVersion），UNKNOWN 与 reported 分开呈现。
 */
@Service
public class DecisionBudget {
    private final DecisionStore store;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public DecisionBudget(DecisionStore store) {
        this.store = store;
    }

    private Object lock(String bookId) {
        return locks.computeIfAbsent(bookId, k -> new Object());
    }

    /**
     * 原子预留：成功返回 attemptId（已持久 RESERVED）；限额不足返回 null；
     * 账本损坏/落盘失败抛 BudgetUnavailableException（禁止新外呼）。
     */
    public String reserve(String bookId, String purpose, long amountMinor, Long limitMinor)
            throws IOException {
        if (bookId == null || purpose == null || purpose.isBlank() || amountMinor < 0)
            throw new IllegalArgumentException("预留参数非法");
        if (limitMinor == null || limitMinor < 0) return null;
        synchronized (lock(bookId)) {
            long[] totals = store.budgetTotals(bookId);
            long active = addExact(totals[0], totals[1]);
            if (overLimit(active, amountMinor, limitMinor)) return null;
            String attemptId = UUID.randomUUID().toString();
            store.saveAttempt(bookId, new AttemptLedger(attemptId, purpose, amountMinor,
                    AttemptState.RESERVED, null, Instant.now()));
            return attemptId;
        }
    }

    /** 发送意图：RESERVED→SEND_INTENT，持久化成功才允许进入传输。 */
    public void markSendIntent(String bookId, String physicalAttemptId) throws IOException {
        synchronized (lock(bookId)) {
            AttemptLedger current = loadOrFail(bookId, physicalAttemptId);
            if (current.state() != AttemptState.RESERVED) return;
            store.saveAttempt(bookId, new AttemptLedger(current.physicalAttemptId(),
                    current.purpose(), current.reservedMinor(), AttemptState.SEND_INTENT,
                    null, Instant.now()));
        }
    }

    /** 已发送但费用未知（超时/无 usage/执行未知/取消）：保留预留，不记 0。 */
    public void retainUnknown(String bookId, String physicalAttemptId) throws IOException {
        transition(bookId, physicalAttemptId, AttemptState.RETAINED_UNKNOWN, null);
    }

    /** 按真实合同结算：预留转实报。 */
    public void settleReported(String bookId, String physicalAttemptId, long actualMinor)
            throws IOException {
        if (actualMinor < 0) throw new IllegalArgumentException("实报为负");
        transition(bookId, physicalAttemptId, AttemptState.SETTLED_REPORTED, actualMinor);
    }

    /** 可证明未发送：仅该 attempt 释放一次。 */
    public void releaseNotSent(String bookId, String physicalAttemptId) throws IOException {
        synchronized (lock(bookId)) {
            AttemptLedger current = loadOrFail(bookId, physicalAttemptId);
            if (current.state() == AttemptState.RELEASED_NOT_SENT
                    || current.state() == AttemptState.SETTLED_REPORTED
                    || current.state() == AttemptState.RETAINED_UNKNOWN) return;
            if (current.state() != AttemptState.RESERVED) return;
            store.saveAttempt(bookId, new AttemptLedger(current.physicalAttemptId(),
                    current.purpose(), current.reservedMinor(), AttemptState.RELEASED_NOT_SENT,
                    null, Instant.now()));
        }
    }

    private void transition(String bookId, String physicalAttemptId, AttemptState next,
                            Long reported) throws IOException {
        synchronized (lock(bookId)) {
            AttemptLedger current = loadOrFail(bookId, physicalAttemptId);
            if (current.state() == next) return;
            if (current.state() == AttemptState.SETTLED_REPORTED
                    || current.state() == AttemptState.RELEASED_NOT_SENT) return;
            if (current.state() == AttemptState.RETAINED_UNKNOWN && next == AttemptState.RELEASED_NOT_SENT)
                return;
            store.saveAttempt(bookId, new AttemptLedger(current.physicalAttemptId(),
                    current.purpose(), current.reservedMinor(), next, reported, Instant.now()));
        }
    }

    private AttemptLedger loadOrFail(String bookId, String physicalAttemptId) throws IOException {
        Optional<AttemptLedger> attempt = store.loadAttempt(bookId, physicalAttemptId);
        if (attempt.isEmpty())
            throw new BudgetUnavailableException("预留记录缺失，禁止继续外呼");
        return attempt.get();
    }

    private static boolean overLimit(long active, long amount, long limit) {
        try {
            return Math.addExact(active, amount) > limit;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }

    private static long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** 当前未结算预留 + 已实报（损坏抛异常，不回 0）。 */
    public long[] totals(String bookId) throws IOException {
        synchronized (lock(bookId)) {
            return store.budgetTotals(bookId);
        }
    }

    // ---- 兼容旧调用（测试/过渡）：语义收紧为 fail-closed ----
    /** @deprecated 仅测试兼容；生产走 reserve 系列。 */
    @Deprecated
    public boolean tryReserve(String bookId, long amountMinor, Long limitMinor) {
        try {
            return reserve(bookId, "legacy", amountMinor, limitMinor) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** @deprecated 仅测试兼容。 */
    @Deprecated
    public long reservedMinor(String bookId) {
        try {
            return totals(bookId)[0];
        } catch (IOException e) {
            return -1;
        }
    }

    /** @deprecated 仅测试兼容。 */
    @Deprecated
    public long reportedMinor(String bookId) {
        try {
            return totals(bookId)[1];
        } catch (IOException e) {
            return -1;
        }
    }
}
