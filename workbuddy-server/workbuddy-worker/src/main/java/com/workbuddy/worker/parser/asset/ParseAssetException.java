package com.workbuddy.worker.parser.asset;

/**
 * 解析资产处理失败（渲染、编码或对象存储写入）。
 */
public class ParseAssetException extends RuntimeException {

    /**
     * 构造资产处理异常。
     *
     * @param message 失败说明
     */
    public ParseAssetException(String message) {
        super(message);
    }

    /**
     * 构造带原因的资产处理异常。
     *
     * @param message 失败说明
     * @param cause   原始异常
     */
    public ParseAssetException(String message, Throwable cause) {
        super(message, cause);
    }
}
