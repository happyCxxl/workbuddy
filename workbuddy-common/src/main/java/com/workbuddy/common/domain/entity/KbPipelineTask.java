package com.workbuddy.common.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 处理链任务（kb_pipeline_task）：一次执行一条；同一份输入（上游产物）与同一策略版本只允许一条
 * （唯一键 uk_exec，0 表示"没有上游"与"该环节无策略"两档）。
 *
 * <p>机器表：无用户字段与更新留痕字段（仅 create_time）。
 *
 * @author cxxl
 */
@Data
@TableName("kb_pipeline_task")
public class KbPipelineTask {

    /** 主键 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属文件结果 */
    private Long fileResultId;

    /** 环节（PipelineStage 枚举名） */
    private String stage;

    /** 上游产物 ID（0 = 没有上游：解析是链路起点） */
    private Long upstreamProductId;

    /** 策略版本 ID（0 = 该环节无策略：解析/组装；其余取 kb_pipeline_strategy_version.id） */
    private Long strategyVersionId;

    /** 环节策略快照（JSON 文本，触发时固定） */
    private String strategySnapshot;

    /** 成功运行回写的产物 ID（kb_pipeline_product.id；以此产物触发下游的数据源） */
    private Long productId;

    /** 状态（PipelineTaskStatus 枚举名） */
    private String status;

    /** 错误码 */
    private String errorCode;

    /** 错误信息 */
    private String errorMsg;

    /** 创建时间（DB 默认填充） */
    private LocalDateTime createTime;

    /** 开始执行时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;
}
