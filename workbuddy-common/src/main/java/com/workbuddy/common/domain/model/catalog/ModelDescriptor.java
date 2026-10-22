package com.workbuddy.common.domain.model.catalog;

import com.workbuddy.common.enums.embed.EmbedMetric;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.utils.NullUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 模型目录视图：目录端口对外给出的一个模型（厂商归属 + 能力 + 长度 + 价格 + 限额 + 状态）。
 *
 * <p>本类型是平台侧契约语义，落在 common；库表实体与 Mapper 留在 knowledge-biz，端口消费方看不到库结构。
 * 能力是多值（D26）：一个模型可以同时出现在多个能力的候选集里，而限额与健康状态只按模型算一份。
 */
@Data
public class ModelDescriptor {

    /** 厂商码（knowledge.model.vendors 配置里的键） */
    private String vendorCode;

    /** 厂商侧模型名 */
    private String modelKey;

    /** 能力清单（多值） */
    private Set<ModelCapability> capabilities = new LinkedHashSet<>();

    /** 上下文窗口（tokens，可空） */
    private Integer contextWindow;

    /** 最大输入长度（tokens，可空） */
    private Integer inputLimit;

    /** 最大输出长度（tokens，可空） */
    private Integer outputLimit;

    /** 输入每百万 token 单价（可空） */
    private BigDecimal priceInPerM;

    /** 输出每百万 token 单价（可空） */
    private BigDecimal priceOutPerM;

    /** 币种：CNY / USD */
    private String currency;

    /** 每分钟请求数上限（可空） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空） */
    private Integer tpmLimit;

    /** 状态：ENABLED 启用 / DISABLED 停用 */
    private String status;

    /** 向量编码规格（该模型承载向量编码时为非空） */
    private EmbedSpecs embedSpecs;

    /**
     * 是否启用：状态为空按未启用处理。
     *
     * @return 启用为 true
     */
    public boolean enabled() {
        return "ENABLED".equalsIgnoreCase(status);
    }

    /**
     * 是否具备该能力。
     *
     * @param capability 能力
     * @return 具备为 true
     */
    public boolean supports(ModelCapability capability) {
        return NullUtil.isNotNull(capabilities) && capabilities.contains(capability);
    }

    /**
     * 向量模型规格（向量编码专用）：目录里放得下向量元数据的那部分。
     * 其余能力用不到本段，取值为空表示该模型不承载向量编码。
     */
    @Data
    public static class EmbedSpecs {

        /** 向量维度 */
        private Integer dimension;

        /** 度量 */
        private EmbedMetric metric;

        /** 是否归一化（COSINE 度量必须归一化） */
        private Boolean normalized;

        /** 上下文窗口（tokens） */
        private Integer contextWindowTokens;

        /** 单批上限（条） */
        private Integer batchLimit;
    }
}
