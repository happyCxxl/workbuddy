package com.workbuddy.common.dto.request.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 更新知识库请求。
 */
@Data
public class KnowledgeBaseUpdateDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID（必填） */
    @NotNull(message = "知识库 ID 不能为空")
    private Long id;

    /** 知识库名称（必填，≤14；上限口径见 {@link KnowledgeBaseCreateDTO} 同名字段） */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 14, message = "知识库名称最长 14 字符")
    private String name;

    /** 业务场景说明（≤64；上限口径见 {@link KnowledgeBaseCreateDTO} 同名字段） */
    @Size(max = 64, message = "业务场景说明最长 64 字符")
    private String description;

    /** 策略绑定开关：1 开启 / 0 关闭（测评模式，触发必须显式选策略）；null 视为开启 */
    private Integer strategyBindingEnabled;
}
