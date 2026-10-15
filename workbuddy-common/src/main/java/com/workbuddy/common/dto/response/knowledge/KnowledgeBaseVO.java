package com.workbuddy.common.dto.response.knowledge;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库视图对象（status 返回码值 1/0）。
 *
 * @author cxxl
 */
@Data
public class KnowledgeBaseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花 ID，前端按字符串处理防精度丢失） */
    private Long id;

    private String name;

    private String description;

    /** 状态码值：1 启用 / 0 停用（见 KnowledgeBaseStatus） */
    private Integer status;

    /** 策略绑定开关：1 开启 / 0 关闭（测评模式，触发必须显式选策略） */
    private Integer strategyBindingEnabled;

    /** 绑定的切片策略版本行 ID（无绑定为 null） */
    private Long chunkStrategyVersionId;

    /** 绑定的切片策略版本串（如 chunk-hybrid-v1，无绑定为 null） */
    private String chunkStrategyVersion;

    /** 绑定的预处理策略版本行 ID（无绑定为 null） */
    private Long preprocessStrategyVersionId;

    /** 绑定的预处理策略版本串（如 preproc-default-v1，无绑定为 null） */
    private String preprocessStrategyVersion;

    /** 绑定的向量化策略版本行 ID（无绑定为 null） */
    private Long embedStrategyVersionId;

    /** 绑定的向量化策略版本串（如 embed-default-v1，无绑定为 null） */
    private String embedStrategyVersion;

    /**
     * 文档总数：wb_file_result 记录数（一次提交 = 一个任务 = 一行）。
     * 同一文件重复提交会各占一行，这是提交次数而非去重文件数。
     */
    private Long documentCount;

    /** 当前发布索引集合行 ID（无发布为 null；列表回填版本号用） */
    private Long publishedIndexSetId;

    /** 当前已发布索引版本号（如 v3；未发布为 null） */
    private String publishedIndexVersion;

    /** 创建用户ID（无认证上下文为 null） */
    private Long userId;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
