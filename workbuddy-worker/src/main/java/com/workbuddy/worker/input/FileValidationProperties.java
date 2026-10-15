package com.workbuddy.worker.input;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件校验配置（knowledge.file 前缀）。
 * 配置来源 = knowledge-biz 的 config/knowledge-file.yml 同名 kebab-case 键（由主文件 application.yml 的 spring.config.import 清单载入）；未配置时取内置默认值。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.file")
public class FileValidationProperties {

    /** 文件大小上限（字节），默认 100MB；超限即停、不再继续读取 */
    private long maxSize = 100L * 1024 * 1024;

    /**
     * 启用格式白名单（FileFormat 枚举名）。
     * 默认只启用数字版 PDF/DOC/DOCX/XLS/XLSX；
     * PPT/PPTX/TXT/MD/CSV 可识别但预留关闭，需要时经配置项放开。
     */
    private List<String> enabledFormats = List.of("PDF", "DOC", "DOCX", "XLS", "XLSX");

    /** 流式校验内存缓冲阈值（字节），默认 10MB；超阈值部分溢写到临时文件，校验完即删 */
    private long tempFileThreshold = 10L * 1024 * 1024;
}
