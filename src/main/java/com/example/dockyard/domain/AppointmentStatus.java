package com.example.dockyard.domain;

/**
 * 预约 / 车辆在场生命周期：
 * BOOKED/OVERRIDDEN -> GATED_IN -> CALLED -> DOCKED -> UNLOADING -> COMPLETED -> EXITED
 * 另可流转到 CANCELLED / NO_SHOW。
 */
public enum AppointmentStatus {
    BOOKED("已预约"),
    OVERRIDDEN("插单"),
    GATED_IN("等候中"),
    CALLED("已叫号派台"),
    DOCKED("已靠台"),
    UNLOADING("卸货中"),
    COMPLETED("卸货完成"),
    EXITED("已出场"),
    CANCELLED("已取消"),
    NO_SHOW("失约");

    private final String label;

    AppointmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 车辆仍在场内（唯一索引 uq_appt_plate_in_yard 覆盖这些状态） */
    public boolean inYard() {
        return this == GATED_IN || this == CALLED || this == DOCKED
                || this == UNLOADING || this == COMPLETED;
    }

    /** 月台已被占用（唯一索引 uq_dock_occupancy 覆盖这些状态） */
    public boolean occupiesDock() {
        return this == CALLED || this == DOCKED || this == UNLOADING || this == COMPLETED;
    }
}
