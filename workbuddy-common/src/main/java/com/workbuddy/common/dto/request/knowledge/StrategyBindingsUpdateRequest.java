package com.workbuddy.common.dto.request.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 知识库策略集合批量绑定请求：一次调用设置整套（发布=知识库策略集合）。
 * 局部更新语义：仅处理请求中出现的类型，未提及类型不动；strategyVersionId 空 = 解绑。
 *
 * @author cxxl
 */
@Data
public class StrategyBindingsUpdateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 策略绑定项列表（type 不重复；至少一项） */
    @Valid
    @NotEmpty(message = "绑定列表不能为空")
    private List<StrategyBindItem> bindings;

    /**
     * 单个类型绑定项。
     */
    @Data
    public static class StrategyBindItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 策略类型：PREPROCESS / CHUNK / EMBED（BINDABLE_TYPES 白名单） */
        @NotBlank(message = "策略类型不能为空")
        private String strategyType;

        /** 策略版本行 ID；null = 解绑 */
        private Long strategyVersionId;
    }
}
