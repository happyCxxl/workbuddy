package com.workbuddy.common.dto.request.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建知识库请求。
 */
@Data
public class KnowledgeBaseCreateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识库名称（必填，≤14）
     */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 14, message = "知识库名称最长 14 字符")
    private String name;

    /**
     * 业务场景说明（≤64）。
     */
    @Size(max = 64, message = "业务场景说明最长 64 字符")
    private String description;

    /**
     * 策略绑定开关（必填）：1 开启 / 0 关闭（测评模式，触发必须显式选策略）
     */
    @NotNull(message = "策略绑定开关不能为空")
    @Min(value = 0, message = "策略绑定开关只接受 0（关闭）或 1（开启）")
    @Max(value = 1, message = "策略绑定开关只接受 0（关闭）或 1（开启）")
    private Integer strategyBindingEnabled;
}
