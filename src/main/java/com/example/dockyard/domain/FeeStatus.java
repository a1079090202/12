package com.example.dockyard.domain;

public enum FeeStatus {
    CONFIRMED,  // 已核费，无异议
    DISPUTED,   // 承运商已提异议，待处理
    UPHELD,     // 异议驳回，维持原金额
    ADJUSTED    // 异议成立，金额已调整
}
