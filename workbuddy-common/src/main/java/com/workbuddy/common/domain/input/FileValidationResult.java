package com.workbuddy.common.domain.input;

import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailReason;
import lombok.Data;

/**
 * 文件校验结果。
 * 通过时携带建档三写所需：format（真实格式）/ mimeType（Tika 识别媒体类型，落 wb_source_file.mime_type）
 * / size（流式统计字节数）/ sha256（文件指纹）；失败时携带 failReason
 * （wb_submit_log.fail_reason 存其枚举名），其余字段为空。
 */
@Data
public class FileValidationResult {

    /** 是否通过 */
    private boolean passed;

    /** 失败原因（通过时为 null） */
    private FileValidationFailReason failReason;

    /** 真实格式（Tika 魔数识别，通过时非空） */
    private FileFormat format;

    /** MIME 类型（Tika 识别媒体类型字符串，通过时非空） */
    private String mimeType;

    /** 实际字节数（流式统计，通过时非空） */
    private Long size;

    /** sha256 指纹（流式计算，通过时非空） */
    private String sha256;

    /** 校验通过工厂：携带建档三写所需全部字段。 */
    public static FileValidationResult pass(FileFormat format, String mimeType, Long size, String sha256) {
        FileValidationResult r = new FileValidationResult();
        r.passed = true;
        r.format = format;
        r.mimeType = mimeType;
        r.size = size;
        r.sha256 = sha256;
        return r;
    }

    /** 校验失败工厂：只携带失败原因，其余字段为空。 */
    public static FileValidationResult fail(FileValidationFailReason reason) {
        FileValidationResult r = new FileValidationResult();
        r.passed = false;
        r.failReason = reason;
        return r;
    }
}
