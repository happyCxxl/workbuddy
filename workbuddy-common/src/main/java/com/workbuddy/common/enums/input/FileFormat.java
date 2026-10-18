package com.workbuddy.common.enums.input;

import lombok.Getter;

import java.util.Arrays;

/**
 * 文件格式识别字典（Tika 真实格式全集），不是白名单：
 * 白名单由配置 knowledge.file.enabled-formats 控制（默认 PDF/DOC/DOCX/XLS/XLSX 启用，
 * 其余可识别格式预留、默认关闭），识别依据 = Tika 魔数，不信任扩展名。
 * 纯图片（JPG/PNG/TIFF/BMP）不进本字典，识别后单独走 IMAGE_OCR_RESERVED（OCR 预留）。
 * mimeType 为各格式的真实 MIME 值——单一事实源：校验映射与各解析器路由共用。
 */
@Getter
public enum FileFormat {

    /** 数字版 PDF（默认启用） */
    PDF("application/pdf"),

    /** Word 97-2003（默认启用） */
    DOC("application/msword"),

    /** Word 2007+（默认启用） */
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),

    /** Excel 97-2003（默认启用） */
    XLS("application/vnd.ms-excel"),

    /** Excel 2007+（默认启用） */
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),

    /** PowerPoint 97-2003（预留） */
    PPT("application/vnd.ms-powerpoint"),

    /** PowerPoint 2007+（预留） */
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation"),

    /** 纯文本（预留） */
    TXT("text/plain"),

    /** Markdown（预留） */
    MD("text/markdown"),

    /** CSV（预留） */
    CSV("text/csv");

    private final String mimeType;

    FileFormat(String mimeType) {
        this.mimeType = mimeType;
    }

    /**
     * 按真实 MIME 查找格式。
     *
     * @param mimeType 真实 MIME 类型
     * @return 命中的格式；未知类型为 null
     */
    public static FileFormat ofMimeType(String mimeType) {
        return Arrays.stream(values())
                .filter(f -> f.mimeType.equals(mimeType))
                .findFirst()
                .orElse(null);
    }
}
