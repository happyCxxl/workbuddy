package com.workbuddy.common.dto.response.index;

import com.workbuddy.common.enums.index.IndexShape;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 可构建组合枚举视图（测评模式构建弹窗数据源）：
 * 组合四维 + 产物完整标记 + 构建前预览统计（成员文件数 × 向量数）。
 * fileResultIds = 该组合血统匹配的成员文件（范围收窄时仅计范围内文件）；
 * fileCount = 成员数；vectorCount = Σ 成员最新向量集合 recordCount
 * （含 FAILED/SKIPPED 少量偏差，构建以实际写入行数为准）。
 *
 * @author cxxl
 */
@Data
public class IndexComboVO {

    /** 文件范围模式（枚举组合恒为 ALL） */
    private String fileScopeMode;

    /** 该组合血统匹配的成员文件 ID 列表（范围收窄时仅计范围内文件；雪花字符串序列化） */
    private List<Long> fileResultIds;
    /** 切片策略 name-version */
    private String chunkStrategy;

    /** 向量策略 name-version */
    private String embedStrategy;

    /** 索引形态（当前仅 FULL_VECTOR） */
    private IndexShape shape;

    /** 环节策略映射（stage → 策略 name-version；口径） */
    private Map<String, String> stageStrategies;

    /** 产物完整标记（枚举仅返回完整组合，恒 true） */
    private boolean complete;

    /** 成员文件数（血统匹配该组合的文件数） */
    private int fileCount;

    /** 预览向量数（Σ 最新向量集合 recordCount） */
    private int vectorCount;
}
