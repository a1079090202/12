package com.example.dockyard.domain;

/** 全流程事件类型，写入 operation_event 留痕 */
public enum EventType {
    BOOKED("提交预约"),
    OVERRIDE("调度插单"),
    CANCELLED("取消预约"),
    GATE_IN("门卫放行进场"),
    EARLY_TO_WAITING("早到进入等候区"),
    LATE_FLAG("迟到超宽限标记"),
    CALL("调度叫号派台"),
    DOCK("靠台"),
    START_UNLOAD("开始卸货"),
    COMPLETE("卸货完成"),
    EXIT("出场"),
    FEE_SETTLED("出场核费"),
    DISPUTE_RAISED("承运商提起异议"),
    DISPUTE_REJECTED("异议驳回维持原价"),
    DISPUTE_ADJUSTED("异议成立调整金额");

    private final String label;

    EventType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
