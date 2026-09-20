package studio.bookhtml.service;

/**
 * 额度/配额/无权限类失败，可触发同系通道自动降级。
 * 普通图片问题、配置缺失、取消等仍用 OcrException/ApiException，不参与降级。
 */
public class QuotaExceededException extends OcrException {
    public QuotaExceededException(String message) { super(message); }
    public QuotaExceededException(String message, Throwable cause) { super(message, cause); }
}
