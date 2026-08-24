package interview.homegrown.modules.interview.model;


//面试会话状态
public enum InterviewStatus {

    //面试进行中（含用户已退出界面、计时仍在继续的场景）
    IN_PROGRESS,

    //答题已结束（全部答完 / 超时），待用户点击评估
    PENDING_EVALUATION,

    //已评估完成
    COMPLETED,

    //手动终止（保留）
    TERMINATED

}
