package com.example.dockyard.domain;

public enum DisputeStatus {
    OPEN,       // 待处理
    ADJUSTED,   // 成立，已调整金额
    REJECTED    // 驳回，维持原金额
}
