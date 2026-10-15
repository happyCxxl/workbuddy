package com.workbuddy.common.dto.response;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体。
 *
 * <p>code=0 表示成功；错误语义由 code 表达。
 *
 * @param <T> 业务数据类型
 * @author cxxl
 */
@Setter
@Getter
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功码 */
    public static final int SUCCESS = 0;

    /** 通用失败码 */
    public static final int FAILURE = 1;

    private int code;

    private String msg;

    private T data;

    private Long timestamp;

    public R() {
    }

    public R(int code, String msg, T data, Long timestamp) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = timestamp;
    }

    /** 成功响应（默认提示语） */
    public static <T> R<T> ok(T data) {
        return ok(data, "操作成功");
    }

    /** 成功响应（自定义提示语） */
    public static <T> R<T> ok(T data, String msg) {
        return new R<>(SUCCESS, msg, data, System.currentTimeMillis());
    }

    /** 失败响应（通用失败码） */
    public static <T> R<T> failed(String msg) {
        return new R<>(FAILURE, msg, null, System.currentTimeMillis());
    }

}
