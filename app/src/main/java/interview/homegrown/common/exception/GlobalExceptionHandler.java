package interview.homegrown.common.exception;


import interview.homegrown.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // SSE 端点（produces=text/event-stream）的响应 content-type 会被预设成 event-stream，
    // 直接返回 Result 会因找不到对应转换器而渲染失败（客户端拿不到任何响应 → 挂到网关
    // 超时 524）。所有出口统一显式声明 application/json，让预流阶段的异常按普通接口
    // 语义返回（原状态码 + Result JSON，前端 openSse 的 !res.ok 路径与 cleanErr 兼容）。

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常：code={}, message={}", e.getErrorCode().getCode(), e.getMessage());
        return json(HttpStatus.OK, e.getErrorCode().getCode(), e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return json(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage() == null ? fe.getField() : fe.getDefaultMessage())
                .orElse("请求参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return json(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result<Void>> handleResponseStatusException(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        int code = status instanceof HttpStatus hs ? hs.value() : status.value();
        String reason = e.getReason() != null ? e.getReason() : "请求处理异常";
        if (code >= 500) {
            log.error("HTTP 状态异常：status={}, reason={}", code, reason, e);
        } else {
            log.warn("HTTP 状态异常：status={}, reason={}", code, reason);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(code, reason));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("接口不支持请求方法：method={}, supported={}", e.getMethod(), e.getSupportedHttpMethods());
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(MediaType.APPLICATION_JSON);
        if (e.getSupportedHttpMethods() != null) response.headers(headers -> headers.setAllow(e.getSupportedHttpMethods()));
        return response.body(Result.error(405, "当前接口不支持此请求方式，请确认前后端已同步更新"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<Result<Void>> json(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.error(code, message));
    }
}
