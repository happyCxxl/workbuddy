package com.workbuddy.common.dto.request.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 创建知识库请求。
 *
 * @author cxxl
 */
@Data
public class KnowledgeBaseCreateDto implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 知识库名称（必填，≤14）。
     *
     * <p>上限按卡片标题行的可用宽度定：列宽下限 380px 的卡片内宽 344px，扣掉立方体图标
     * （38px）、两个 11px 间距与状态标签（约 68px）后留给库名约 216px；库名 15px 字、汉字全宽
     * 15px/字 → 216 ÷ 15 = 14 字。数字/字母更窄，14 字以内必然整行显示得下。
     */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 14, message = "知识库名称最长 14 字符")
    private String name;

    /**
     * 业务场景说明（≤64）。
     *
     * <p>上限按卡片正文宽度定：12px 字约 28 字/行，64 字约 2 行。
     */
    @Size(max = 64, message = "业务场景说明最长 64 字符")
    private String description;

    /** 策略绑定开关：1 开启（默认）/ 0 关闭（测评模式，触发必须显式选策略）；null 视为开启 */
    private Integer strategyBindingEnabled;
}
