package studio.bookhtml.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,String>> api(ApiException e) { return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, HttpMessageNotReadableException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String,String>> validation(Exception e) { return ResponseEntity.badRequest().body(Map.of("message", "请求参数无效")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String,String>> tooLarge() { return ResponseEntity.status(413).body(Map.of("message", "PDF 文件超过上传大小限制")); }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String,String>> notFound() { return ResponseEntity.status(404).body(Map.of("message", "未找到该资源")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,String>> general(Exception e) { return ResponseEntity.internalServerError().body(Map.of("message", "服务器处理失败，请查看本地日志")); }
}
