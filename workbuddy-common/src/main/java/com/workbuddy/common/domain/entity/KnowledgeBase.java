package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 知识库：一个业务场景 = 一个知识库。
 * 平台五件套继承自 {@link BaseInfo}。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("kb_knowledge_base")
public class KnowledgeBase extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 业务场景说明 */
    private String description;

    /** 状态：1 启用（ACTIVE）/ 0 停用（DISABLED），码值定义见 KnowledgeBaseStatus */
    private Integer status;

    /** 策略绑定开关：1 开启（触发默认走 KB 绑定策略）/ 0 关闭（测评模式，触发必须显式选策略）；null 视为开启 */
    private Integer strategyBindingEnabled;

    /** 绑定处理策略版本（三件套之一） */
    private Long bindingProfileVersionId;

    /** 索引配置（三件套之一，JSON 原样存储） */
    private String indexConfig;

    /** 默认检索规则（三件套之一） */
    private Long defaultRuleId;

    /** 当前发布索引 */
    private Long publishedIndexSetId;

    /** 创建用户ID（占位 NULL；TODO 接认证后取登录用户） */
    private Long userId;
}
