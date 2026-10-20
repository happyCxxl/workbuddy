package com.workbuddy.biz.service.support.common;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.dto.response.lineage.LineageCapabilityVO;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 执行树快照解析：任务策略快照 → 策略版本，产物能力快照 → 结构化能力字段。
 *
 * <p>快照为空、字段缺失或解析失败一律下发 null，不回落原文、不构造默认策略。
 */
@Slf4j
public final class LineageCapabilitySupport {

    private LineageCapabilitySupport() {
    }

    /**
     * 策略快照 → name-version 串。
     *
     * @param snapshot 任务上的策略快照 JSON 文本，可空
     * @return name-version 串；快照为空、字段缺失或解析失败返回 null
     */
    public static String strategyVersion(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            Map<String, Object> map = JsonUtil.toMap(snapshot);
            String name = String.valueOf(map.getOrDefault("name", ""));
            String version = String.valueOf(map.getOrDefault("version", ""));
            if (StrUtil.isBlank(name) || StrUtil.isBlank(version)) {
                return null;
            }
            return name + "-" + version;
        } catch (Exception e) {
            log.warn("策略快照解析失败, snapshot={}", StrUtil.maxLength(snapshot, 200));
            return null;
        }
    }

    /**
     * 能力快照 JSON 文本 → 结构化对象。
     *
     * <p>**解析成对象，不透传原文**：product.capabilitySnapshot 存的是
     * {@link CapabilitySnapshot} 序列化后的 JSON；解析在服务层完成，下发的就是可用字段
     * （与 {@link #strategyVersion(String)} 同一口径）。
     *
     * <p>解析失败返回 null（**不回落原文**）：脏数据不继续往上层传。
     *
     * @param snapshot 产物上的能力快照 JSON 文本，可空
     * @return 结构化能力快照；快照为空或解析失败返回 null
     */
    public static LineageCapabilityVO capability(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            CapabilitySnapshot src = JsonUtil.toObject(snapshot, CapabilitySnapshot.class);
            if (NullUtil.isNull(src)) {
                return null;
            }
            LineageCapabilityVO vo = new LineageCapabilityVO();
            vo.setParserName(src.getParserName());
            vo.setParserVersion(src.getParserVersion());
            vo.setOcr(toCapabilityRef(src.getOcr()));
            vo.setLayout(toCapabilityRef(src.getLayout()));
            vo.setTable(toCapabilityRef(src.getTable()));
            return vo;
        } catch (Exception e) {
            log.warn("能力快照解析失败, snapshot={}", StrUtil.maxLength(snapshot, 200));
            return null;
        }
    }

    /** 领域层能力引用 → 接口层能力引用；入参为空则返回空 */
    private static LineageCapabilityVO.CapabilityRefVO toCapabilityRef(CapabilitySnapshot.CapabilityRef src) {
        if (NullUtil.isNull(src)) {
            return null;
        }
        LineageCapabilityVO.CapabilityRefVO vo = new LineageCapabilityVO.CapabilityRefVO();
        vo.setModel(src.getModel());
        vo.setVersion(src.getVersion());
        return vo;
    }
}
