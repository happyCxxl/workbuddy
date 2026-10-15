package com.workbuddy.common.dto.request.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 新增与编辑模型的请求体：厂商码、模型名、能力、价格、限额、状态与生效区间。
 *
 * <p>厂商码取配置（`knowledge.model.vendors`）里的键，服务层在保存时校验；能力是多值，取值按字典解析；
 * 价格四列与限额两列都可留空，给了值必须非负。生效区间两段接受日期与日期时间两种文本，留空表示开放区间。
 *
 * @author cxxl
 */
@Data
public class ModelSaveDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 厂商码（必填，取值必须在配置里） */
    private String vendorCode;

    /** 厂商侧模型名（必填） */
    private String modelKey;

    /** 页面展示名（可留空，留空取模型名） */
    private String displayName;

    /** 能力清单（多值，必填至少一项） */
    private List<String> capabilities;

    /** 上下文窗口（tokens，可空） */
    private Integer contextWindow;

    /** 输入每百万 token 单价（可空，非负） */
    private BigDecimal priceInPerM;

    /** 输出每百万 token 单价（可空，非负） */
    private BigDecimal priceOutPerM;

    /** 图像按页单价（可空，非负） */
    private BigDecimal priceImagePerPage;

    /** 嵌入按千次单价（可空，非负） */
    private BigDecimal pricePerThousandCalls;

    /** 币种（可空，留空取 CNY；取值 CNY / USD） */
    private String currency;

    /** 每分钟请求数上限（可空，非负） */
    private Integer rpmLimit;

    /** 每分钟 token 上限（可空，非负） */
    private Integer tpmLimit;

    /** 状态（可空，留空取 ENABLED；取值 ENABLED / DISABLED） */
    private String status;

    /** 生效开始时间（可空 = 立即生效；`yyyy-MM-dd` 或 `yyyy-MM-ddTHH:mm:ss`） */
    private String effectiveFrom;

    /** 生效结束时间（可空 = 长期有效；写法与开始时间一致） */
    private String effectiveTo;

    /** 备注（可空） */
    private String remark;
}
