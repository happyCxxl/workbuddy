package com.workbuddy.biz.web;

import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.web.R;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 全局异常出口单测。
 *
 * <p>守护三条口径：业务异常原样透出错误码与原因、参数校验取第一条字段错误
 * （错误码 40001）、未知异常只回通用系统错误（不泄露堆栈与内部信息）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /**
     * 业务异常：错误码与可读原因原样透出，HTTP 状态保持 200。
     *
     * <p>守护 {@code handleKnowledgeException}：码值改写即失败（前端按码分支）。
     */
    @Test
    void knowledgeExceptionShouldPassThroughCodeAndMessage() {
        R<Void> result = handler.handleKnowledgeException(
                new WorkbuddyException(ErrorCode.KB_NOT_FOUND, "知识库 9 不存在"));

        assertEquals(ErrorCode.KB_NOT_FOUND.getCode(), result.getCode());
        assertEquals("知识库 9 不存在", result.getMsg());
    }

    /**
     * 参数校验失败取第一条字段错误（字段名 + 缺省消息）。
     *
     * <p>守护 {@code handleMethodArgumentNotValidException}：消息拼接口径改错即失败。
     */
    @Test
    void validationShouldReportFirstFieldError() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "query");
        binding.addError(new FieldError("query", "name", "不能为空"));
        binding.addError(new FieldError("query", "size", "必须为正数"));
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        when(e.getBindingResult()).thenReturn(binding);

        R<Void> result = handler.handleMethodArgumentNotValidException(e);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), result.getCode());
        assertEquals("name 不能为空", result.getMsg());
    }

    /**
     * 字段错误缺失时回落参数错误缺省文案。
     *
     * <p>守护 {@code handleMethodArgumentNotValidException} 的兜底：无错误时抛 NPE 即失败。
     */
    @Test
    void validationShouldFallBackToDefaultMessageWhenNoFieldErrors() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "query");
        MethodArgumentNotValidException e = mock(MethodArgumentNotValidException.class);
        when(e.getBindingResult()).thenReturn(binding);

        R<Void> result = handler.handleMethodArgumentNotValidException(e);

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), result.getCode());
        assertEquals(ErrorCode.PARAM_INVALID.getMessage(), result.getMsg());
    }

    /**
     * 请求体缺失与 JSON 解析错误各有专属文案。
     *
     * <p>守护 {@code handleHttpMessageNotReadable} 的文案区分：两类错误混为一谈即失败。
     */
    @Test
    void unreadableBodyShouldDistinguishMissingAndParseError() {
        assertEquals("请求体缺失", handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("Required request body is missing")).getMsg());
        assertEquals("JSON 格式错误", handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("JSON parse error: Unexpected token")).getMsg());
    }

    /**
     * 其它不可读异常回通用解析失败文案。
     *
     * <p>守护 {@code handleHttpMessageNotReadable} 的兜底分支。
     */
    @Test
    void unreadableBodyShouldFallBackToGenericMessage() {
        assertEquals("请求体解析失败，", handler.handleHttpMessageNotReadable(
                new HttpMessageNotReadableException("something else")).getMsg());
    }

    /**
     * 上传超限回可读的 40422 与配置上限，不落到 40500 通用系统异常。
     *
     * <p>守护 {@code handleMaxUploadSizeExceeded}：回到通用 40500 或丢掉上限文案即失败。
     */
    @Test
    void uploadSizeExceededShouldReturnRejectReason() {
        R<Void> result = handler.handleMaxUploadSizeExceeded(
                new MaxUploadSizeExceededException(104857600L));

        assertEquals(ErrorCode.FILE_UPLOAD_REJECTED.getCode(), result.getCode());
        assertEquals("文件超过大小上限（上限 100MB）", result.getMsg());
    }

    /**
     * 未知异常只回通用系统错误，不泄露内部信息。
     *
     * <p>守护 {@code handleException}：把异常消息透出即失败（堆栈与内部信息外泄）。
     */
    @Test
    void unknownExceptionShouldReturnGenericSystemError() {
        R<Void> result = handler.handleException(new IllegalStateException("数据库密码错误"));

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), result.getCode());
        assertEquals(ErrorCode.SYSTEM_ERROR.getMessage(), result.getMsg());
        assertNull(result.getData());
    }
}
