package studio.bookhtml.store;

import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

/**
 * R03：版本冲突。携带安全的当前版本号供调用方刷新，
 * 不在消息或日志中放入整页内容。
 */
public class PageConflictException extends ApiException {
    private final int currentRevision;

    public PageConflictException(int currentRevision, String message) {
        super(HttpStatus.CONFLICT, message);
        this.currentRevision = currentRevision;
    }

    public int currentRevision() {
        return currentRevision;
    }
}
