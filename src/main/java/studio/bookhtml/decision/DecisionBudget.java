package studio.bookhtml.decision;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * J04：预算预留。发送前原子预留本书范围预算；所有物理 attempt 与额外视觉调用都计入；
 * usage 缺失/timeout/执行未知记 UNKNOWN 并保留预留；整数最小货币单位记账，不用浮点。
 * 注意：单价未知时预留的是本地核算单位，不是供应商实际账单；未知费用仍单列。
 */
@Service
public class DecisionBudget {
    private final DecisionStore store;
    private final ConcurrentHashMap<String, AtomicLong> reserved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> reported = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public DecisionBudget(DecisionStore store) {
        this.store = store;
    }

    private Object lock(String bookId) {
        return locks.computeIfAbsent(bookId, k -> new Object());
    }

    private AtomicLong reservedOf(String bookId) {
        return reserved.computeIfAbsent(bookId, k -> new AtomicLong(restore(bookId).reservedMinor()));
    }

    private AtomicLong reportedOf(String bookId) {
        return reported.computeIfAbsent(bookId, k -> new AtomicLong(restore(bookId).reportedMinor()));
    }

    private DecisionStore.BudgetState restore(String bookId) {
        try {
            Optional<DecisionStore.BudgetState> state = store.loadBudgetState(bookId);
            return state.orElseGet(() -> new DecisionStore.BudgetState(0, 0, 0, Instant.now()));
        } catch (IOException e) {
            return new DecisionStore.BudgetState(0, 0, 0, Instant.now());
        }
    }

    private boolean persist(String bookId) {
        try {
            store.saveBudgetState(bookId, new DecisionStore.BudgetState(
                    reservedOf(bookId).get(), reportedOf(bookId).get(), 0, Instant.now()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 原子预留：限额内扣减成功返回 true；不足返回 false（拒绝发生在外呼前）。
     * limitMinor 为 null 表示未设预算，一律拒绝。
     * T57：预留落盘失败则回滚并拒绝，不发送。
     */
    public boolean tryReserve(String bookId, long amountMinor, Long limitMinor) {
        if (bookId == null || amountMinor < 0 || limitMinor == null || limitMinor < 0) return false;
        synchronized (lock(bookId)) {
            long current = reservedOf(bookId).get();
            if (current + amountMinor > limitMinor) return false;
            reservedOf(bookId).addAndGet(amountMinor);
            if (!persist(bookId)) {
                reservedOf(bookId).addAndGet(-amountMinor);
                return false;
            }
            return true;
        }
    }

    /** 未发送即取消：释放预留（落盘尽力，内存已更新为安全方向）。 */
    public void release(String bookId, long amountMinor) {
        if (bookId == null || amountMinor <= 0) return;
        synchronized (lock(bookId)) {
            reservedOf(bookId).updateAndGet(v -> Math.max(0, v - amountMinor));
            persist(bookId);
        }
    }

    /** 已发送但费用未知（超时/无 usage/执行未知）：保留预留，不记 0。 */
    public void settleUnknown(String bookId) {
        persist(bookId);
    }

    /** 按真实合同结算：预留转实报。 */
    public void settleReported(String bookId, long reservedAmountMinor, long actualMinor) {
        if (bookId == null) return;
        synchronized (lock(bookId)) {
            reservedOf(bookId).updateAndGet(v -> Math.max(0, v - reservedAmountMinor));
            reportedOf(bookId).addAndGet(Math.max(0, actualMinor));
            persist(bookId);
        }
    }

    public long reservedMinor(String bookId) {
        return reservedOf(bookId).get();
    }

    public long reportedMinor(String bookId) {
        return reportedOf(bookId).get();
    }
}
