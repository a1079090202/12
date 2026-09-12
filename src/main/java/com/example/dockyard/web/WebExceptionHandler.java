package com.example.dockyard.web;

import com.example.dockyard.service.BusinessRuleException;
import com.example.dockyard.service.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

import jakarta.validation.ConstraintViolationException;
import java.time.format.DateTimeParseException;

/**
 * 全局异常映射：
 *  - ForbiddenException（水平越权）          -> 403
 *  - BusinessRuleException（业务规则违反）   -> 400 友好错误页，不暴露堆栈
 *  - 参数缺失/非法/超长/解析失败             -> 400 通用提示，不回显用户输入细节
 *  - 其余未预期异常                           -> 500，服务端记日志
 */
@ControllerAdvice
public class WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WebExceptionHandler.class);

    @ExceptionHandler(ForbiddenException.class)
    public ModelAndView forbidden(ForbiddenException ex) {
        log.warn("访问被拒绝：{}", ex.getMessage());
        return page("error/403", HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ModelAndView business(BusinessRuleException ex) {
        return page("error/business", HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class,
            DateTimeParseException.class,
            IllegalArgumentException.class
    })
    public ModelAndView badRequest(Exception ex) {
        log.info("请求参数非法：{}", ex.getMessage());
        return page("error/business", HttpStatus.BAD_REQUEST, "提交的参数不合法，请核对表单内容后重试。");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ModelAndView dataTooLong(DataIntegrityViolationException ex) {
        log.info("数据违反约束：{}", ex.getMostSpecificCause().getMessage());
        return page("error/business", HttpStatus.BAD_REQUEST, "提交的数据超出长度限制或违反唯一约束，请精简后重试。");
    }

    /**
     * @PreAuthorize / 方法安全抛出的 AccessDeniedException 不能被兜底 500 吞掉：
     * 重抛回 DispatcherServlet，由 ExceptionTranslationFilter 走 /403。
     */
    @ExceptionHandler(AccessDeniedException.class)
    public void accessDenied(AccessDeniedException ex) throws AccessDeniedException {
        throw ex;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception ex) {
        log.error("未处理异常", ex);
        return page("error/500", HttpStatus.INTERNAL_SERVER_ERROR, "服务器开小差了，请稍后重试或联系管理员。");
    }

    private ModelAndView page(String view, HttpStatus status, String message) {
        ModelAndView mav = new ModelAndView(view);
        mav.addObject("message", message);
        mav.setStatus(status);
        return mav;
    }
}
