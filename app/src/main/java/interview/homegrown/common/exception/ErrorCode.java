package interview.homegrown.common.exception;

public enum ErrorCode {
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "禁止访问"),
    NOT_FOUND(404, "资源不存在"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // ========== 业务 ==========
    RESUME_NOT_FOUND(1001, "简历不存在"),
    RESUME_PARSE_FAILED(1002, "简历解析失败"),
    RESUME_ANALYSIS_FAILED(1003, "简历分析失败"),
    FILE_TOO_LARGE(1004, "文件过大"),
    FILE_TYPE_NOT_SUPPORTED(1005, "文件类型不支持"),
    DUPLICATE_FILE(1006, "文件已存在"),

    INTERVIEW_SESSION_NOT_FOUND(2001, "面试会话不存在"),
    INTERVIEW_ALREADY_COMPLETED(2002, "面试已结束"),
    QUESTION_GENERATION_FAILED(2003, "出题失败"),

    KNOWLEDGE_BASE_NOT_FOUND(3001, "知识库不存在"),
    VECTORIZATION_FAILED(3002, "向量化失败");

    private final int code;
    private final String message;

    ErrorCode(int code,String message){
        this.code = code;
        this.message = message;
    }

    public int getCode(){
        return code;
    }

    public String getMessage(){

        return message;
    }
}