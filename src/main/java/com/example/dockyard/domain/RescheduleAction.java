package com.example.dockyard.domain;

/** 受影响预约的处置动作：改期到新时段 / 标记改约待定。 */
public enum RescheduleAction {
    RESCHEDULED("已改期"),
    PENDING("改约待定");

    private final String label;

    RescheduleAction(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
