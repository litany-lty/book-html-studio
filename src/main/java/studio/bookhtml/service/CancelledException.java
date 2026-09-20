package studio.bookhtml.service;

public class CancelledException extends RuntimeException {
    public CancelledException() { super("任务已取消"); }
}
