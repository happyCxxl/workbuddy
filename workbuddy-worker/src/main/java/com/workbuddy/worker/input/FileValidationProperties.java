package com.workbuddy.worker.input;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文件校验配置（knowledge.file 前缀）。
 * 同名 kebab-case 键在 knowledge-biz 的 config/workbuddy-file.yml（由主文件 application.yml 的
 * spring.config.import 清单载入）；字段不带代码侧默认值，配置面是唯一基线。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.file")
public class FileValidationProperties {

    /** 文件大小上限（字节）；超限即停、不再继续读取 */
    private long maxSize;

    /**
     * 启用格式白名单（FileFormat 枚举名）。
     * 白名单里的每种格式都必须有解析器接得住，启动期由 `ParserWhitelistChecker` 校验。
     */
    private List<String> enabledFormats;

    /** 流式校验内存缓冲阈值（字节）；超阈值部分溢写到临时文件，校验完即删 */
    private long tempFileThreshold;
}
