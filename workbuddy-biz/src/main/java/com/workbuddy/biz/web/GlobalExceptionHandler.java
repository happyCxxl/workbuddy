package com.workbuddy.biz.web;

import com.workbuddy.common.web.R;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：
 * 业务异常 → 错误码 + 可读原因（warn）；参数校验失败/请求体缺失或格式错误 → 40001；未知异常 → 40500 兜底（error 带堆栈，不暴露给客户端）。
 * HTTP 状态码保持 200，错误语义走 R.code。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务拒绝：错误码与可读原因原样透出，HTTP 状态保持 200。
     *
     * @param e 业务异常
     * @return 统一失败响应
     */
    @ExceptionHandler(WorkbuddyException.class)
    public R<Void> handleKnowledgeException(WorkbuddyException e) {
        log.warn("===> GlobalExceptionHandler 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return new R<>(e.getCode(), e.getMessage(), null, null);
    }

    /**
     * 参数校验失败：取第一条字段错误的消息，错误码 40001。
     *
     * @param e 校验异常
     * @return 统一失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .findFirst()
                .orElse(ErrorCode.PARAM_INVALID.getMessage());
        log.warn("===> GlobalExceptionHandler 参数校验失败: {}", msg);
        return new R<>(ErrorCode.PARAM_INVALID.getCode(), msg, null, null);
    }

    /**
     * 请求体缺失或 JSON 格式错误：区分两种文案，错误码 40001。
     *
     * @param e 消息不可读异常
     * @return 统一失败响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        String msg = "请求体解析失败，";
        String detail = e.getMessage();
        if (NullUtil.isNotNull(detail) && detail.contains("Required request body is missing")) {
            msg = "请求体缺失";
        } else if (NullUtil.isNotNull(detail) && detail.contains("JSON parse error")) {
            msg = "JSON 格式错误";
        }
        log.warn("===> GlobalExceptionHandler 请求体读取失败, type={}", e.getClass().getSimpleName());
        return new R<>(ErrorCode.PARAM_INVALID.getCode(), msg, null, null);
    }

    /**
     * 未知异常兜底：记 error 带堆栈，对外只回通用系统错误。
     *
     * @param e 未捕获的异常
     * @return 统一失败响应
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("===> GlobalExceptionHandler 系统异常", e);
        return new R<>(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage(), null, null);
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + " " + fieldError.getDefaultMessage();
    }
}
