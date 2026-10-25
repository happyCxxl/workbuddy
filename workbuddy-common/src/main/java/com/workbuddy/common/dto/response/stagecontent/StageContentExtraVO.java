package com.workbuddy.common.dto.response.stagecontent;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 产物内容项的环节专属附加信息：字段按环节分组，未使用的字段为 null 且不输出。
 *
 * <p>判别依据是条目自身的 {@code type}（元素/片类型）与查询的环节，不在本类内重复表达；
 * 同一字段在不同环节含义一致（如 {@code page} 始终是文档页码）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StageContentExtraVO {

    /** 解析：元素来源（native / ocr 等，取自 ParseSource.source） */
    private String source;

    /** 解析：解析器标识（取自 ParseSource.provider） */
    private String provider;

    /** 文档页码：解析 / 组装 / 预处理有页概念，其余环节为 null */
    private Integer page;

    /** 表格区域的行数（解析 / 组装） */
    private Integer rows;

    /** 表格区域的列数（解析 / 组装） */
    private Integer cols;

    /** 元素边界框（原文预览按它在页面上画高亮框，单位 pt、左上角原点） */
    private BBoxVO bbox;

    /** 解析：字体摘要（名称 + 字号 + 粗体） */
    private String font;

    /** 解析：是否目录行候选 */
    private Boolean tocCandidate;

    /** 解析：产物溯源路径 */
    private String provenance;

    /** 解析：是否为规则降级产物（表格规则失败转段落等） */
    private Boolean degraded;

    /** 解析：降级原因 */
    private String degradeReason;

    /** 解析：Word 段落样式名（渲染器把样式名写在段落 DOM 上） */
    private String style;

    /** 解析：Excel 工作表名 */
    private String sheetName;

    /** 解析：Excel 单元格行下标（0 基，与表格区域高亮同口径） */
    private Integer row;

    /** 解析：Excel 单元格列下标（0 基） */
    private Integer col;

    /** 解析：合并单元格的行跨度 */
    private Integer rowSpan;

    /** 解析：合并单元格的列跨度 */
    private Integer colSpan;

    /** 解析：是否表头单元格 */
    private Boolean isHeader;

    /** 解析 / 预处理：表格单元格明细（二维数组的 JSON 文本，原样下发不做截断） */
    private String cells;

    /** 组装：标题层级（非标题元素为 null） */
    private Integer level;

    /** 预处理：元素原文（与 displayText 的差异来自清洗；仅在需要对照时下发） */
    private String rawText;

    /** 预处理：元素上的结构标记（页眉页脚 / 目录 / 重复段等） */
    private List<String> marks;

    /** 预处理：抽取出的规范化字段（JSON 文本，字段名 → 值） */
    private String normalizedFields;

    /** 预处理：非 KEEP 的处置轨迹（JSON 文本；未处理过的元素不下发，避免响应体膨胀） */
    private String trace;

    /** 切片 / 向量化：切片 ID */
    private String chunkId;

    /** 切片：切片内容类型（正文 / 表格 / 图片 / 父片等） */
    private String contentType;

    /** 切片：标题路径（父级标题以分隔符连接） */
    private String titlePath;

    /** 切片 / 向量化：字符数 */
    private Integer charCount;

    /** 切片 / 向量化：估算 token 数 */
    private Integer tokenCount;

    /** 切片：集合内顺序号 */
    private Integer orderNo;

    /** 切片：父片 ID（无父片为 null） */
    private String parentChunkId;

    /** 切片：覆盖的文档页码区间（如 1-2） */
    private String pageRange;

    /** 切片：表格来源引用 */
    private String tableRef;

    /** 向量化：是否命中缓存 */
    private Boolean cacheHit;

    /** 向量化：模型调用请求 ID（排查与计量用） */
    private String requestId;
}
