package com.workbuddy.common.convert.system;

import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.enums.input.SubmitStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 系统域转换：提交日志实体（编译期生成实现，运行期无反射）。
 *
 * <p>通过路径走生成映射；失败路径需要常量与空指纹占位，用默认方法书写。
 * 公共列在此显式忽略：多来源映射无法继承单来源原型（见 `JAVA-DTO-013`）。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface SystemConverter {

    /**
     * 通过校验的提交日志：文件名取元数据，指纹取校验结果，状态 PASS。
     *
     * @param knowledgeBaseId  知识库 ID
     * @param request          提交请求
     * @param validationResult 文件校验结果
     * @param metadata         文件元数据
     * @param fileResultId     文件结果 ID
     * @param userId           当前用户 ID（可空）
     * @return 提交日志（未保存）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "delFlag", ignore = true)
    @Mapping(target = "createBy", ignore = true)
    @Mapping(target = "createTime", ignore = true)
    @Mapping(target = "updateBy", ignore = true)
    @Mapping(target = "updateTime", ignore = true)
    @Mapping(target = "knowledgeBaseId", source = "knowledgeBaseId")
    @Mapping(target = "requestId", source = "request.requestId")
    @Mapping(target = "fileId", source = "request.fileId")
    @Mapping(target = "sha256", source = "validationResult.sha256")
    @Mapping(target = "fileName", source = "metadata.fileName")
    @Mapping(target = "fileResultId", source = "fileResultId")
    @Mapping(target = "status", expression = "java(com.workbuddy.common.enums.input.SubmitStatus.PASS.name())")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "failReason", ignore = true)
    WbSubmitLog toSubmitLogPass(Long knowledgeBaseId, FileSubmitDTO request,
                                FileValidationResult validationResult, FileMetadata metadata,
                                Long fileResultId, Long userId);

    /**
     * 校验失败的提交日志：文件名尽力取（取不到空串），指纹算不出时存空串占位，状态 FAIL。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求
     * @param failReason      校验失败原因
     * @param fileName        文件名（可空）
     * @param userId          当前用户 ID（可空）
     * @return 提交日志（未保存）
     */
    default WbSubmitLog toSubmitLogFail(Long knowledgeBaseId, FileSubmitDTO request, String failReason,
                                        String fileName, Long userId) {
        WbSubmitLog failLog = new WbSubmitLog();
        failLog.setKnowledgeBaseId(knowledgeBaseId);
        failLog.setRequestId(request.getRequestId());
        failLog.setFileId(request.getFileId());
        failLog.setFileName(fileName);
        // sha256 列 NOT NULL 无默认值；校验失败（尤其文件不存在）算不出指纹，存空串占位
        failLog.setSha256("");
        failLog.setStatus(SubmitStatus.FAIL.name());
        failLog.setFailReason(failReason);
        failLog.setUserId(userId);
        return failLog;
    }
}
