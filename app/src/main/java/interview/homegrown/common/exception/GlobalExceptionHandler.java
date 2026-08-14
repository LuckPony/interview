package interview.homegrown.common.exception;


import interview.homegrown.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e){
        log.warn("业务异常：code={}, message={}", e.getErrorCode().getCode(),e.getMessage());
        return Result.error(e.getErrorCode());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return Result.error(ErrorCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage() == null ? fe.getField() : fe.getDefaultMessage())
                .orElse("请求参数校验失败");
        log.warn("参数校验失败: {}", msg);
        return Result.error(ErrorCode.BAD_REQUEST, msg);
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
        return ResponseEntity.status(status).body(Result.error(code, reason));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未预期异常", e);
        return Result.error(ErrorCode.INTERNAL_ERROR);
    }
}
