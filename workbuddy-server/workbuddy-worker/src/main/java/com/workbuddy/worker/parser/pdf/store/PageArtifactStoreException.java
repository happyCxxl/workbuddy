package com.workbuddy.worker.parser.pdf.store;

/**
 * 页产物存储异常：读写失败、窗口缺失或产物损坏。
 * 解析环节将其归为执行失败，不伪装成文件损坏。
 */
public class PageArtifactStoreException extends RuntimeException {

    /**
     * 构造存储异常。
     *
     * @param message 异常说明
     */
    public PageArtifactStoreException(String message) {
        super(message);
    }

    /**
     * 构造存储异常。
     *
     * @param message 异常说明
     * @param cause   原因
     */
    public PageArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
