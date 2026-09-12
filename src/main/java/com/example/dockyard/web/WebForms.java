package com.example.dockyard.web;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/** Web 层表单小工具 */
final class WebForms {

    private WebForms() {}

    /** 取第一条字段校验错误作为面向用户的提示（不回显原始输入） */
    static String firstError(BindingResult br) {
        FieldError fe = br.getFieldError();
        if (fe != null && fe.getDefaultMessage() != null) {
            return fe.getDefaultMessage();
        }
        return "提交的参数不合法，请核对表单内容后重试。";
    }
}
