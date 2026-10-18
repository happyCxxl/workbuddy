package com.workbuddy.worker.structure.craft;

import com.workbuddy.common.domain.structure.UnifiedElement;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素标准化结果：统一元素列表 + 未识别类型的跳过计数（解析类型名 → 跳过元素数）。
 * 计数用于把"类型集合不匹配导致的丢元素"落成产物质量告警，不再静默。
 */
@Data
public class NormalizeOutcome {

    /** 统一元素列表（未识别类型的元素不在其中） */
    private List<UnifiedElement> elements = new ArrayList<>();

    /** 未识别类型的计数（空类型记为 (blank)） */
    private Map<String, Integer> unmappedTypeCounts = new LinkedHashMap<>();

    /**
     * 未识别类型的跳过元素总数。
     *
     * @return 未能映射到统一模型的元素数
     */
    public int unmappedElementCount() {
        return unmappedTypeCounts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
