package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 索引集合：一知识库一行，承载二级发布指针 currentPublishedVersionId。
 * 机器表：append-only、仅 create_time、不挂平台五件套（与 kb_pipeline_task 同款）。
 *
 * @author cxxl
 */
@Data
@TableName("kb_index_set")
public class KbIndexSet {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属知识库 */
    private Long knowledgeBaseId;

    /** 当前发布版本 ID（二级发布指针；检索读取） */
    private Long currentPublishedVersionId;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;
}
