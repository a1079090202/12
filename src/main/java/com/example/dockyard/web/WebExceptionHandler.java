package com.example.dockyard.web;

import com.example.dockyard.service.BusinessRuleException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** 业务规则违反：回到友好错误页（HTTP 400），不暴露堆栈 */
@ControllerAdvice
public class WebExceptionHandler {

    @ExceptionHandler(BusinessRuleException.class)
    public String business(BusinessRuleException ex, Model model) {
        model.addAttribute("message", ex.getMessage());
        return "error/business";
    }
}
