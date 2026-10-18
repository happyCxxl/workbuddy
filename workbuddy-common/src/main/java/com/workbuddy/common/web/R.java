package com.workbuddy.common.web;

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

    /** 供反序列化用的无参构造。 */
    public R() {
    }

    /**
     * 全字段构造。
     *
     * @param code      状态码（0 成功）
     * @param msg       提示消息
     * @param data      业务数据
     * @param timestamp 响应时间戳
     */
    public R(int code, String msg, T data, Long timestamp) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = timestamp;
    }

    /**
     * 成功响应（默认提示语）。
     *
     * @param data 业务数据
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> R<T> ok(T data) {
        return ok(data, "操作成功");
    }

    /**
     * 成功响应（自定义提示语）。
     *
     * @param data 业务数据
     * @param msg  提示消息
     * @param <T>  业务数据类型
     * @return 成功响应
     */
    public static <T> R<T> ok(T data, String msg) {
        return new R<>(SUCCESS, msg, data, System.currentTimeMillis());
    }

    /**
     * 失败响应（通用失败码）。
     *
     * @param msg 提示消息
     * @param <T> 业务数据类型
     * @return 失败响应，业务数据为空
     */
    public static <T> R<T> failed(String msg) {
        return new R<>(FAILURE, msg, null, System.currentTimeMillis());
    }

}
