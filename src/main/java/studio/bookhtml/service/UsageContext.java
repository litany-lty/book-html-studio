package studio.bookhtml.service;

/** Explicit worker scope. Reused threads restore their previous context in finally. */
public final class UsageContext {
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<>();
    private UsageContext() {}
    public record Value(String bookId, Integer pageNumber, String operation, String taskId) {
        public Value(String bookId, Integer pageNumber, String operation) { this(bookId,pageNumber,operation,null); }
    }
    public static Value current() { return CURRENT.get(); }
    public static Scope open(String bookId, Integer pageNumber, String operation) {
        return open(bookId, pageNumber, operation, null);
    }
    public static Scope open(String bookId, Integer pageNumber, String operation, String taskId) {
        Value previous = CURRENT.get();
        CURRENT.set(new Value(bookId, pageNumber, operation, taskId));
        return new Scope(previous);
    }
    public static final class Scope implements AutoCloseable {
        private Value previous;
        private boolean closed;
        private Scope(Value previous) { this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            previous = null;
        }
    }
}
