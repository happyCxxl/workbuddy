package com.workbuddy.common.dto.request.index;

import com.workbuddy.common.enums.index.IndexShape;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合快照请求体：文件范围 × 索引形态 × 环节策略映射。
 */
@Data
public class ComboSnapshotDTO {

    /** 文件范围模式：ALL（全部）/ LIST（评测冻结集） */
    private String fileScopeMode = "ALL";

    /** LIST 模式下的文件结果 ID 列表（ALL 模式为空） */
    private List<Long> fileResultIds;

    /** 索引形态 */
    private IndexShape shape = IndexShape.FULL_VECTOR;

    /** 环节策略映射：环节名 → 策略 name-version */
    private Map<String, String> stageStrategies = new LinkedHashMap<>();
}
