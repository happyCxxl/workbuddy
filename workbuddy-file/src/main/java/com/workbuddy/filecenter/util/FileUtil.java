package com.workbuddy.filecenter.util;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 文件操作工具：文件校验的原语判定（读流统计 / 魔数识别 / 试解析探测 / 白名单与后缀等）与单次下载流缓冲。
 * 校验流程怎么编排是调用方的事，本类只提供原子操作。
 */
@Slf4j
public final class FileUtil {

    /** 读流缓冲 8KB */
    private static final int BUFFER_SIZE = 8192;

    /** 试解析输出上限（只探测结构，不取全文；1MB 足量） */
    private static final int PROBE_CHAR_LIMIT = 1024 * 1024;

    private static final Tika TIKA = new Tika();

    private FileUtil() {
    }

    /**
     * 单遍读流统计字节数（不缓冲）；超过上限即停并返回当前累计值，由调用方比对判定。
     *
     * @param in      输入流（调用方负责关闭）
     * @param maxSize 大小上限（字节）
     * @return 流式统计字节数（超上限时返回超过上限的累计值）
     * @throws IOException 读流失败
     */
    public static long count(InputStream in, long maxSize) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxSize) {
                return total;
            }
        }
        return total;
    }

    /**
     * 单遍读流统计字节数并写入校验缓冲（同步更新 sha256）；超过上限即停并返回当前累计值。
     *
     * @param in      输入流（调用方负责关闭）
     * @param maxSize 大小上限（字节）
     * @param buffer  校验缓冲（同一校验内可重开流读取）
     * @return 流式统计字节数（超上限时返回超过上限的累计值）
     * @throws IOException 读流或写缓冲失败
     */
    public static long countAndBuffer(InputStream in, long maxSize, StreamBuffer buffer) throws IOException {
        byte[] chunk = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > maxSize) {
                return total;
            }
            buffer.write(chunk, 0, n);
        }
        return total;
    }

    /** 空文件判定：字节数为 0 视为损坏（空文件无可解析内容）。 */
    public static boolean isEmpty(Long size) {
        return NullUtil.isNotNull(size) && size == 0;
    }

    /**
     * Tika 魔数识别媒体类型。
     *
     * @param in 输入流（调用方负责关闭）
     * @return 识别媒体类型；未识别为 null
     * @throws IOException 读流失败
     */
    public static String detect(InputStream in) throws IOException {
        return TIKA.detect(in);
    }

    /**
     * 损坏/加密试解析探测：结构可解析返回 null，异常链含 encrypt/password 返回 FILE_ENCRYPTED，其余返回 FILE_CORRUPTED。
     *
     * @param in 输入流（调用方负责关闭）
     * @return null（可解析）或失败原因
     */
    public static FileValidationFailReason probe(InputStream in) {
        try {
            new AutoDetectParser().parse(in, new BodyContentHandler(PROBE_CHAR_LIMIT), new Metadata());
            return null;
        } catch (Exception e) {
            return isEncryptedException(e) ? FileValidationFailReason.FILE_ENCRYPTED
                    : FileValidationFailReason.FILE_CORRUPTED;
        }
    }

    /** Office OOXML 受保护文档判定（Tika detect 返回 x-tika-ooxml-protected）。 */
    public static boolean isProtected(String mediaType) {
        return NullUtil.isNotNull(mediaType) && mediaType.contains("ooxml-protected");
    }

    /** JPG/PNG/TIFF/BMP 可识别但 OCR 未开放。 */
    public static boolean isReservedImage(String mediaType) {
        if (NullUtil.isNull(mediaType)) {
            return false;
        }
        return switch (mediaType) {
            case "image/jpeg", "image/png", "image/tiff", "image/bmp" -> true;
            default -> false;
        };
    }

    /** Tika 媒体类型 → FileFormat 字典映射（魔数真实识别结果）；未知类型返回 null。 */
    public static FileFormat ofFormat(String mediaType) {
        if (NullUtil.isNull(mediaType)) {
            return null;
        }
        return FileFormat.ofMimeType(mediaType);
    }

    /** 白名单判定：FileFormat 枚举名在启用清单内。 */
    public static boolean whitelisted(FileFormat format, List<String> enabled) {
        Set<String> whitelist = new HashSet<>(ObjectUtil.defaultIfNull(enabled, List.of()));
        return whitelist.contains(format.name());
    }

    /** 取文件名后缀（不含点，保留原大小写；大小写归一由 {@link FileFormat#ofExtension} 处理）；文件名无点或为空时返回 null */
    public static String extensionOf(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1);
    }

    /** 加密判定启发式：异常链（深度上限 10）消息含 encrypt/password 即视为加密。 */
    private static boolean isEncryptedException(Exception e) {
        Throwable t = e;
        int depth = 0;
        while (NullUtil.isNotNull(t) && depth++ < 10) {
            String message = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("encrypt") || message.contains("password")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 单次下载流缓冲：内存缓冲至阈值，超阈值溢写临时文件；同一校验内可重复开流读取（魔数/试解析不重复下载）。
     */
    public static final class StreamBuffer {

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
        public StreamBuffer(long threshold) {
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
                    log.warn("===> FileUtil 校验临时文件删除失败, tempFile={}", tempFile, e);
                    tempFile.toFile().deleteOnExit();
                }
            }
        }
    }
}
