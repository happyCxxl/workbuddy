package com.workbuddy.worker.parser.capability.prompt;

import com.workbuddy.common.domain.model.OutputFormat;
import com.workbuddy.common.domain.model.OutputSpec;

/**
 * 三项解析能力的提示词与输出 JSON Schema（解析侧单一事实源）。
 *
 * <p>每项给出三样：给模型的自然语言要求、模型的输出约束（JSON Schema 文本），以及把固定要求与本次
 * 区域信息拼成一条提示词的入口。schema 的字段名与既有领域类型（TableResult / LayoutResult /
 * StructuredOcrResult）一一对应，映射侧按同一份契约回填。
 *
 * @author cxxl
 */
public final class CapabilityPrompts {

    /** 表格结构识别：输出约束的根结构为「行列数 + 单元格清单 + 置信度」 */
    private static final String TABLE_SCHEMA = """
            {"type":"object","properties":{\
            "rows":{"type":"integer","minimum":1,"description":"总行数"},\
            "cols":{"type":"integer","minimum":1,"description":"总列数"},\
            "cells":{"type":"array","items":{"type":"object","properties":{\
            "row":{"type":"integer","minimum":0,"description":"行号，从 0 起"},\
            "col":{"type":"integer","minimum":0,"description":"列号，从 0 起"},\
            "rowSpan":{"type":"integer","minimum":1,"description":"行跨度，不合并为 1"},\
            "colSpan":{"type":"integer","minimum":1,"description":"列跨度，不合并为 1"},\
            "text":{"type":"string","description":"单元格文本"},\
            "isHeader":{"type":"boolean","description":"是否表头"},\
            "confidence":{"type":"number","minimum":0,"maximum":1}},\
            "required":["row","col","text"],"additionalProperties":false}},\
            "confidence":{"type":"number","minimum":0,"maximum":1,"description":"整体置信度"}},\
            "required":["rows","cols","cells"],"additionalProperties":false}""";

    /** 版面分析：输出约束的根结构为「区域清单 + 阅读顺序」 */
    private static final String LAYOUT_SCHEMA = """
            {"type":"object","properties":{\
            "regions":{"type":"array","items":{"type":"object","properties":{\
            "type":{"type":"string","enum":["title","text","table","figure","header","footer","stamp"],\
            "description":"区域类型"},\
            "bbox":{"type":"object","properties":{\
            "x":{"type":"number","description":"左边界，图像坐标"},\
            "y":{"type":"number","description":"上边界，图像坐标"},\
            "width":{"type":"number","minimum":0},\
            "height":{"type":"number","minimum":0}},\
            "required":["x","y","width","height"],"additionalProperties":false},\
            "confidence":{"type":"number","minimum":0,"maximum":1}},\
            "required":["type","bbox"],"additionalProperties":false}},\
            "order":{"type":"array","items":{"type":"integer","minimum":0},\
            "description":"阅读顺序，元素为 regions 的下标"}},\
            "required":["regions","order"],"additionalProperties":false}""";

    /** 结构化 OCR：输出约束的根结构为「逐行文本 + 行包围盒 + 置信度」 */
    private static final String OCR_SCHEMA = """
            {"type":"object","properties":{\
            "lines":{"type":"array","items":{"type":"object","properties":{\
            "text":{"type":"string","description":"行文本"},\
            "bbox":{"type":"object","properties":{\
            "x":{"type":"number","description":"左边界，图像坐标"},\
            "y":{"type":"number","description":"上边界，图像坐标"},\
            "width":{"type":"number","minimum":0},\
            "height":{"type":"number","minimum":0}},\
            "required":["x","y","width","height"],"additionalProperties":false},\
            "confidence":{"type":"number","minimum":0,"maximum":1}},\
            "required":["text"],"additionalProperties":false}}},\
            "required":["lines"],"additionalProperties":false}""";

    /** 表格结构的固定要求（本次区域的行文本随区域信息拼在后面） */
    private static final String TABLE_REQUIREMENT = """
            你在做表格结构识别。按图中表格的视觉网格给出总行列数与每个单元格的四至与文本。
            规则：行列号从 0 起；合并单元格只在起始位置给一条，并把跨度写进 rowSpan / colSpan；
            不合并时两个跨度都为 1；表头行上的单元格把 isHeader 置 true；给不出包围盒时不写 bbox 字段；
            只输出 JSON 对象，字段名与取值类型按给定的输出结构。""";

    /** 版面的固定要求 */
    private static final String LAYOUT_REQUIREMENT = """
            你在做版面分析。按图像给出文字区域清单与阅读顺序。
            规则：区域类型只取 title / text / table / figure / header / footer / stamp 之一；
            包围盒用图像坐标（左上角为原点，单位与图像像素一致）；阅读顺序是一个下标数组，
            元素为区域清单的下标，按阅读先后排列，且每个下标恰好出现一次；
            只输出 JSON 对象，字段名与取值类型按给定的输出结构。""";

    /** 结构化 OCR 的固定要求 */
    private static final String OCR_REQUIREMENT = """
            你在做文字识别。按图像逐行给出识别到的文本。
            规则：一行一条，按阅读顺序排列；行包围盒用图像坐标（左上角为原点，单位与图像像素一致）；
            置信度取 0 到 1 之间的小数；识别不到文字的图给空的行清单；
            只输出 JSON 对象，字段名与取值类型按给定的输出结构。""";

    private CapabilityPrompts() {
    }

    /**
     * 表格结构识别的输出约束：JSON_SCHEMA 格式，schema 取本类常量。
     *
     * @return 输出约束
     */
    public static OutputSpec tableOutput() {
        return jsonSchema(TABLE_SCHEMA);
    }

    /**
     * 版面分析的输出约束：JSON_SCHEMA 格式，schema 取本类常量。
     *
     * @return 输出约束
     */
    public static OutputSpec layoutOutput() {
        return jsonSchema(LAYOUT_SCHEMA);
    }

    /**
     * 结构化 OCR 的输出约束：JSON_SCHEMA 格式，schema 取本类常量。
     *
     * @return 输出约束
     */
    public static OutputSpec ocrOutput() {
        return jsonSchema(OCR_SCHEMA);
    }

    /**
     * 表格结构识别的提示词：固定要求 + 本次区域信息（页码、图像尺寸、区域行文本）。
     *
     * @param pageNo 页码
     * @param width  页面宽（pt）
     * @param height 页面高（pt）
     * @param regionText 区域内的行文本（可空）
     * @return 提示词
     */
    public static String tablePrompt(Integer pageNo, Double width, Double height, String regionText) {
        StringBuilder prompt = new StringBuilder(TABLE_REQUIREMENT);
        prompt.append("\n\n本次区域：页码 ").append(pageNo);
        appendSize(prompt, width, height);
        if (regionText != null && !regionText.isBlank()) {
            prompt.append("\n区域行文本（同时给出，用于校对单元格文本）：\n").append(regionText);
        }
        return prompt.toString();
    }

    /**
     * 版面分析的提示词：固定要求 + 本次页面信息（页码、图像尺寸与 DPI）。
     *
     * @param pageNo 页码
     * @param width  页面宽（pt）
     * @param height 页面高（pt）
     * @param dpi    页面图像 DPI（可空）
     * @return 提示词
     */
    public static String layoutPrompt(Integer pageNo, Double width, Double height, Integer dpi) {
        StringBuilder prompt = new StringBuilder(LAYOUT_REQUIREMENT);
        prompt.append("\n\n本次页面：页码 ").append(pageNo);
        appendSize(prompt, width, height);
        if (dpi != null && dpi > 0) {
            prompt.append("，图像 DPI ").append(dpi);
        }
        prompt.append("。");
        return prompt.toString();
    }

    /**
     * 结构化 OCR 的提示词：固定要求 + 本次区域信息（页码、区域包围盒）。
     *
     * @param pageNo 页码
     * @param bboxText 区域包围盒的文本（可空）
     * @return 提示词
     */
    public static String ocrPrompt(Integer pageNo, String bboxText) {
        StringBuilder prompt = new StringBuilder(OCR_REQUIREMENT);
        prompt.append("\n\n本次区域：页码 ").append(pageNo);
        if (bboxText != null && !bboxText.isBlank()) {
            prompt.append("，包围盒（pt，左上角原点）").append(bboxText);
        }
        prompt.append("。");
        return prompt.toString();
    }

    /** 拼页面尺寸：两项都给出时才拼 */
    private static void appendSize(StringBuilder prompt, Double width, Double height) {
        if (width != null && height != null) {
            prompt.append("，页面尺寸 ").append(width).append(" × ").append(height).append(" pt");
        }
    }

    /** 输出约束：JSON_SCHEMA 格式 + 本类的 schema 文本 */
    private static OutputSpec jsonSchema(String schema) {
        OutputSpec output = new OutputSpec();
        output.setFormat(OutputFormat.JSON_SCHEMA);
        output.setSchema(schema);
        return output;
    }
}
