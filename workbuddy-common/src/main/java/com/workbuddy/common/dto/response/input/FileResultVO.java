package com.workbuddy.common.dto.response.input;

import com.workbuddy.common.dto.response.task.StageStatusVO;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文件结果 VO：执行链工作台列表行（kb_file_result + kb_source_file 摘要 + 各环节最新任务状态）。
 * id 类字段由全局 Long 序列化口径转字符串（见 LongIdJsonConfig），防前端 JS 精度丢失。
 *
 * @author cxxl
 */
@Data
public class FileResultVO {

    /** 文件结果 ID */
    private Long id;

    /** 所属知识库 */
    private Long knowledgeBaseId;

    /** 原始文件引用（kb_source_file.id） */
    private Long sourceFileId;

    /** 文件 ID */
    private String fileId;

    /** 文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 该文件结果所属存储类型码值（kb_file_object.storage_type：minio / local；无档案时为空） */
    private String storageType;

    /** 该文件结果所属数据源实例 ID（kb_file_object.storage_source_id；无档案时为空） */
    private Long storageSourceId;

    /** 该文件结果所属数据源显示名（数据源未注册时为空） */
    private String storageSourceName;

    /** 当前启用的数据源 ID（无启用的数据源时为空） */
    private Long currentStorageSourceId;

    /** 当前启用的数据源类型码值（无启用的数据源时为空） */
    private String currentStorageType;

    /** 当前启用的数据源显示名（无启用的数据源或未注册时为空） */
    private String currentStorageSourceName;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 各环节最新任务状态（无任务的环节不出现在列表中） */
    private List<StageStatusVO> stageStatuses;
}
