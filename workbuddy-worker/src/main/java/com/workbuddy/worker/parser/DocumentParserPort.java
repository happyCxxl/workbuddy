package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseSource;

/**
 * 文档解析器接口（策略：每个文件格式/路径一个实现类，如 POI 路径、PDFBox 路径）。
 * 文件级路由按 {@link #supports(String)}（文档输入环节识别的真实 MIME）分派。
 * 解析不关闭输入流。
 *
 * @author cxxl
 */
public interface DocumentParserPort {

    /** 支持的 MIME 类型（Tika 真实识别结果） */
    boolean supports(String mimeType);

    /**
     * 执行原生解析，产出 native 路（元素 + 页级指标 + 解析事实）。
     *
     * @param context 解析上下文（文件引用 + 流 + 阈值配置）
     * @return native 路解析结果
     */
    ParseSource parse(ParseContext context);

    /** 能力名称（如 poi / pdfbox），落 capabilitySnapshot */
    String capabilityName();

    /** 能力版本（实测依赖版本，如 5.4.0 / 3.0.4） */
    String capabilityVersion();
}
