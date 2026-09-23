package studio.bookhtml.service;

import java.util.Objects;
import java.util.UUID;

/** One enhancement budget, explicitly propagated to child workers. No image or text retained. */
public final class QwenExecutionScope implements AutoCloseable {
    public record Value(String bookId, int pageNumber, UUID executionId,
                        QwenRequestGate.Budget budget, boolean foreground, long attemptSeq) {}
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<>();
    private final Value previous;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    private QwenExecutionScope(Value next) { previous = CURRENT.get(); CURRENT.set(next); }
    public static Value current() { return CURRENT.get(); }
    public static QwenExecutionScope open(String book, int page, QwenRequestGate gate, boolean foreground) {
        if (book == null || page < 1) throw new IllegalArgumentException("missing page scope");
        Value old = CURRENT.get();
        if (old != null && old.bookId().equals(book) && old.pageNumber() == page)
            return new QwenExecutionScope(old);
        return new QwenExecutionScope(new Value(book,page,UUID.randomUUID(),
                gate == null ? new QwenRequestGate.Budget(8) : gate.newBudget(),foreground,0));
    }
    /** The page engine binds all helpers and child workers to its durable attempt. */
    public static QwenExecutionScope open(studio.bookhtml.domain.PageAttempt attempt,
                                          QwenRequestGate gate, boolean foreground) {
        Objects.requireNonNull(attempt);
        Value old = CURRENT.get();
        if (old != null) {
            if (!old.bookId().equals(attempt.bookId()) || old.pageNumber()!=attempt.pageNumber()
                    || !old.executionId().equals(attempt.attemptId()) || old.attemptSeq()!=attempt.generation())
                throw new IllegalStateException("cannot replace an active page execution identity");
            return new QwenExecutionScope(old);
        }
        return new QwenExecutionScope(new Value(attempt.bookId(),attempt.pageNumber(),attempt.attemptId(),
                gate == null ? new QwenRequestGate.Budget(8) : gate.newBudget(),foreground,attempt.generation()));
    }
    public static QwenExecutionScope attach(Value value) { return new QwenExecutionScope(Objects.requireNonNull(value)); }
    public static boolean foregroundOr(boolean fallback) {
        Value value = CURRENT.get(); return value == null ? fallback : value.foreground();
    }
    public static QwenRequestGate.Budget budgetOr(QwenRequestGate.Budget fallback) {
        Value value = CURRENT.get();
        if (value != null && fallback != null && value.budget() != fallback)
            throw new IllegalArgumentException("mismatched physical call budget");
        return value == null ? fallback : value.budget();
    }
    @Override public void close() {
        if (owner != Thread.currentThread()) throw new IllegalStateException("scope closed on another thread");
        if (closed) return;
        closed = true;
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
