package com.workbuddy.common.exception;

import com.workbuddy.common.error.ErrorCode;
import lombok.Getter;

import java.io.Serial;

/**
 * 统一业务异常：携带错误码，默认消息取错误码文案。
 */
@Getter
public class KnowledgeException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public KnowledgeException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * @param detail 补充原因（覆盖默认消息）
     */
    public KnowledgeException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
