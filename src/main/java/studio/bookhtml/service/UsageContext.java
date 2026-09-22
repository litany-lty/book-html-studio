package studio.bookhtml.service;

/** Explicit worker scope. Reused threads restore their previous context in finally. */
public final class UsageContext {
    private static final ThreadLocal<Value> CURRENT = new ThreadLocal<>();
    private UsageContext() {}
    public record Value(String bookId, Integer pageNumber, String operation) {}
    public static Value current() { return CURRENT.get(); }
    public static Scope open(String bookId, Integer pageNumber, String operation) {
        Value previous = CURRENT.get();
        CURRENT.set(new Value(bookId, pageNumber, operation));
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
