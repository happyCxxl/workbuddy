package com.workbuddy.common.convert.knowledge;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 文件提交转换：提交日志/文件结果 → VO 与提交响应。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface InputConverter {

    /**
     * 提交日志实体 → 提交记录 VO（字段同名一一映射）。
     *
     * @param log 提交日志实体
     * @return 提交记录 VO
     */
    SubmitLogVO toSubmitLogVO(WbSubmitLog log);

    /**
     * 文件结果实体 + 来源文件 → 文件结果 VO：来源文件字段按前缀取值，缺失时留空。
     *
     * @param result     文件结果实体
     * @param sourceFile 来源文件实体（可空）
     * @return 文件结果 VO
     */
    @Mapping(target = "id", source = "result.id")
    @Mapping(target = "createTime", source = "result.createTime")
    @Mapping(target = "fileId", source = "sourceFile.fileId")
    @Mapping(target = "fileName", source = "sourceFile.fileName")
    @Mapping(target = "fileSize", source = "sourceFile.fileSize")
    @Mapping(target = "storageType", ignore = true)
    @Mapping(target = "storageSourceId", ignore = true)
    @Mapping(target = "storageSourceName", ignore = true)
    @Mapping(target = "currentStorageSourceId", ignore = true)
    @Mapping(target = "currentStorageType", ignore = true)
    @Mapping(target = "currentStorageSourceName", ignore = true)
    @Mapping(target = "stageStatuses", ignore = true)
    FileResultVO toFileResultVO(WbFileResult result, WbSourceFile sourceFile);

    /**
     * 提交日志实体 + 解析任务 ID → 提交响应（无任务传 null）。
     *
     * @param log         提交日志实体
     * @param parseTaskId 解析任务 ID
     * @return 提交响应
     */
    default FileSubmitVO toSubmitResponse(WbSubmitLog log, Long parseTaskId) {
        return new FileSubmitVO(toSubmitLogVO(log), parseTaskId);
    }
}
