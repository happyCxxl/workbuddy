package com.workbuddy.worker.input;

import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 校验期缓冲：内存缓冲至阈值，超阈值溢写临时文件；同一校验内可重复开流读取。
 * 全量校验步骤共用一份实例：写入方（大小统计步骤）同时更新 sha256 摘要，
 * 读取方（魔数识别/试解析步骤）经 {@link #openStream()} 重开流，不重复下载。
 */
@Slf4j
public class SpillBuffer {

    private final long threshold;
    private final MessageDigest digest;
    private ByteArrayOutputStream memory;
    private OutputStream target;
    private Path tempFile;

    /**
     * 构造缓冲：小于阈值时留在内存，超出后才溢写临时文件。
     *
     * @param threshold 溢写阈值（字节）
     */
    public SpillBuffer(long threshold) {
        this.threshold = threshold;
        this.memory = new ByteArrayOutputStream();
        this.target = this.memory;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 SHA-256；防御性兜底，不吞异常语义
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 写入缓冲：更新摘要；内存缓冲超阈值时转写临时文件。
     *
     * @param buffer 字节数组
     * @param offset 起始偏移
     * @param length 写入长度
     * @throws IOException 写临时文件失败
     */
    public void write(byte[] buffer, int offset, int length) throws IOException {
        digest.update(buffer, offset, length);
        if (NullUtil.isNotNull(tempFile)) {
            target.write(buffer, offset, length);
            return;
        }
        if (memory.size() + length > threshold) {
            Path tmp = Files.createTempFile("kb-validate-", ".tmp");
            OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp));
            memory.writeTo(out);
            memory = null;
            target = out;
            tempFile = tmp;
        }
        target.write(buffer, offset, length);
    }

    /**
     * 重开读取流：溢写场景先 flush 落盘再开流。
     *
     * @return 从头读取的流
     * @throws IOException 打开临时文件失败
     */
    public InputStream openStream() throws IOException {
        if (NullUtil.isNull(tempFile)) {
            return new ByteArrayInputStream(memory.toByteArray());
        }
        // 溢写缓冲必须落盘后再开读流
        target.flush();
        return new BufferedInputStream(Files.newInputStream(tempFile));
    }

    /**
     * 输出小写十六进制 sha256 指纹。
     *
     * @return 内容指纹
     */
    public String sha256Hex() {
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 关闭目标流并删除临时文件；删除失败注册 deleteOnExit 兜底（不影响校验结果）。 */
    public void close() {
        if (NullUtil.isNotNull(target)) {
            try {
                target.close();
            } catch (IOException ignored) {
                // 关闭失败不影响校验结果，临时文件删除兜底
            }
        }
        if (NullUtil.isNotNull(tempFile)) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                log.warn("===> SpillBuffer 校验临时文件删除失败, tempFile={}", tempFile, e);
                tempFile.toFile().deleteOnExit();
            }
        }
    }
}
