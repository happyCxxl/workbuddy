package com.workbuddy.common.dto.request.stage;

import com.workbuddy.common.dto.request.page.PageQueryDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 环节详情查询条件（解析 / 组装 / 预处理 / 切片 / 向量化 / 产物内容详情共用）。
 *
 * <p>分页参数 `current` / `size` 继承统一分页基类 `PageQueryDTO`，只在产物内容接口上生效，
 * 用于逐页取内容项（大文档不一次取回）。
 * docPage 同样只在产物内容接口上生效，用于只取某个文档页的元素；
 * status 同样只在产物内容接口上生效，用于只取某些处置状态的元素（预处理环节核对剔除内容用）；
 * contentType/fallback/hasParent 同样只在产物内容接口上生效，用于收窄切片内容（切片环节按类型 / 兜底 / 父子查看用）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StageDetailQueryDTO extends PageQueryDTO {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务 ID（可选；不传取最新任务，传了则查该次运行详情） */
    private Long taskId;

    /** 文档页码（可选；传了只回该页元素，从 1 起） */
    private Integer docPage;

    /** 处置状态（可选；逗号分隔，传了只回这些状态的元素） */
    private String status;

    /** 切片内容类型（可选；ChunkContentType 枚举名，只对 CHUNK 生效） */
    private String contentType;

    /** 是否只取兜底片（可选；true 只要兜底片、false 排除兜底片，只对 CHUNK 生效） */
    private Boolean fallback;

    /** 是否有父片（可选；true 只要子片、false 只要父片与孤儿片，只对 CHUNK 生效） */
    private Boolean hasParent;
}
