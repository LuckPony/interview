package interview.homegrown.modules.interview.model;


//面试会话状态
public enum InterviewStatus {

    //面试进行中
    IN_PROGRESS,

    //答题已结束（全部答完 / 超时 / 用户主动结束），待用户点击评估
    PENDING_EVALUATION,

    //已评估完成
    COMPLETED,

    //手动终止（保留）
    TERMINATED

}
