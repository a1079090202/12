package com.example.dockyard.service;

/**
 * 数据归属/授权违规（水平越权）：仍是一种业务规则违反（服务层测试可按
 * BusinessRuleException 断言），但 Web 层单独映射为 HTTP 403，
 * 与普通 400 业务错误区分开，便于监控识别授权事件。
 */
public class ForbiddenException extends BusinessRuleException {
    public ForbiddenException(String message) {
        super(message);
    }
}
