package interview.homegrown.common.result;

import interview.homegrown.common.exception.ErrorCode;

public record Result<T>(
        int code,
        String message,
        T data
) {
    public static <T> Result<T> success(T data) {
        return new Result<>(200, "success", data);
    }
    public static <T> Result<T> success(){
        return new Result<>(200, "success", null);
    }
    public static <T> Result<T> error(int code, String message){
        return new Result<>(code,message, null);
    }
    public static <T> Result<T> error(ErrorCode errorCode){
        return new Result<>(errorCode.getCode(), errorCode.getMessage(),null);
    }
    public static <T> Result<T> error(ErrorCode errorCode,String detail){
        return new Result<>(errorCode.getCode(),errorCode.getMessage() + "：" + detail,null);
    }
}