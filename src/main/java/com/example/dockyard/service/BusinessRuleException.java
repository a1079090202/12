package com.example.dockyard.service;

/** 业务规则被违反（容量已满、状态不符、重复进场/靠台、并发抢占失败等） */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
