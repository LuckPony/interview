package interview.homegrown.common.exception;

public class BusinessException extends RuntimeException{
    private final interview.homegrown.common.exception.ErrorCode errorCode;

    public BusinessException(interview.homegrown.common.exception.ErrorCode errorCode){
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(interview.homegrown.common.exception.ErrorCode errorCode, String detail ){
        super(errorCode.getMessage() + ":" + detail);
        this.errorCode = errorCode;
    }
    public interview.homegrown.common.exception.ErrorCode getErrorCode() {
        return errorCode;
    }
}
