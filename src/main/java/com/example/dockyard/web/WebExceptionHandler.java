package com.example.dockyard.web;

import com.example.dockyard.service.BusinessRuleException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.format.DateTimeParseException;

/**
 * 异常 → 友好页面的统一映射：
 *  - 业务规则违反 / 入参校验失败：400，页面提示具体原因；
 *  - 其余未预期异常：500，通用错误页，不向客户端暴露堆栈。
 * 注意：不处理 AccessDeniedException——交由 Spring Security 过滤器链返回 403。
 */
@ControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(BusinessRuleException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String business(BusinessRuleException ex, org.springframework.ui.Model model) {
        model.addAttribute("message", ex.getMessage());
        return "error/business";
    }

    /** Bean Validation（表单对象绑定）失败：取首条校验信息展示 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String validation(BindException ex, org.springframework.ui.Model model) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getDefaultMessage())
                .orElse("提交的参数不合法，请检查后重试");
        model.addAttribute("message", message);
        return "error/business";
    }

    /** 缺参 / 类型转换失败 / 时间解析失败 / JSON 不可读 / 超长落库：一律 400，不回显细节 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            DateTimeParseException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class,
            DataIntegrityViolationException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String badRequest(Exception ex, org.springframework.ui.Model model) {
        log.debug("请求参数不合法: {}", ex.getMessage());
        model.addAttribute("message", "提交的参数不合法，请检查后重试");
        return "error/business";
    }

    /**
     * 归属校验 / @PreAuthorize 抛出的 AccessDeniedException 必须原样穿透：
     * 否则下面的通用 Exception 兜底会把它渲染成 500。
     * 重抛后异常回到过滤器链，由 ExceptionTranslationFilter 统一返回 403。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public void accessDenied(org.springframework.security.access.AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public String unexpected(Exception ex) {
        log.error("未预期的服务端异常", ex);
        return "error";
    }
}
