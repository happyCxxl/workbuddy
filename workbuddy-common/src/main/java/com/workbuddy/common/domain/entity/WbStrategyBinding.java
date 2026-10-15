package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.workbuddy.common.domain.base.BaseInfo;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 知识库-策略绑定：知识库按策略类型绑定一个策略版本。
 * 上层应用接入后按知识库直接取用绑定策略；切片触发解析顺序两档：
 * 显式传参 → KB 绑定（两档都不命中报 40438）。
 * 解绑为逻辑删除；重新绑定复用原行（uk 不冲突）。
 *
 * @author cxxl
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("wb_knowledge_strategy_binding")
public class WbStrategyBinding extends BaseInfo {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（雪花，应用显式传 id；DDL AUTO_INCREMENT 仅冗余默认值） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 知识库 ID */
    private Long knowledgeBaseId;

    /** 策略类型：PREPROCESS / CHUNK / EMBED */
    private String strategyType;

    /** 策略版本行 ID（wb_knowledge_pipeline_strategy_version.id） */
    private Long strategyVersionId;
}
