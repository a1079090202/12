package com.example.dockyard.service;

/** 业务规则被违反（容量已满、状态不符、重复进场/靠台、并发抢占失败等） */
public class BusinessRuleException extends RuntimeException {

    /** 可选机器可读原因码（如 DOCK_OCCUPIED / DOCK_MAINTENANCE），供调用方区分分支 */
    private final String reasonCode;

    public BusinessRuleException(String message) {
        this(message, null);
    }

    public BusinessRuleException(String message, String reasonCode) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
