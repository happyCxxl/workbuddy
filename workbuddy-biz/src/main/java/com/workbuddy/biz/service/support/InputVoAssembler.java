package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

/**
 * 文档输入 VO 组装器。
 *
 * <p>职责：提交日志/文件结果实体 → 输入环节对外 VO/响应的纯映射（不查库、不做业务判断）；
 * 需合成来源文件信息时 {@link WbSourceFile} 由外部传入，本类只做字段搬运。
 */
@Component
public class InputVoAssembler {

    /**
     * 提交日志实体 → 提交记录 VO（字段一一映射，无加工逻辑）。
     *
     * @param log 提交日志实体
     * @return 提交记录 VO
     */
    public SubmitLogVO toSubmitLogVO(WbSubmitLog log) {
        SubmitLogVO vo = new SubmitLogVO();
        vo.setId(log.getId());
        vo.setKnowledgeBaseId(log.getKnowledgeBaseId());
        vo.setRequestId(log.getRequestId());
        vo.setFileId(log.getFileId());
        vo.setSha256(log.getSha256());
        vo.setFileName(log.getFileName());
        vo.setFileResultId(log.getFileResultId());
        vo.setStatus(log.getStatus());
        vo.setFailReason(log.getFailReason());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }

    /**
     * 提交日志实体 + 解析任务 ID → 提交响应（无任务传 null；纯映射不查库）。
     *
     * @param log         提交日志实体
     * @param parseTaskId 解析任务 ID（wb_knowledge_pipeline_task.id；无任务传 null）
     * @return 提交响应
     */
    public FileSubmitVO toSubmitResponse(WbSubmitLog log, Long parseTaskId) {
        return new FileSubmitVO(toSubmitLogVO(log), parseTaskId);
    }

    /**
     * 文件结果实体 + 来源文件 → 文件结果 VO（fileId/文件名/大小取自来源文件，来源文件缺失则留空）。
     *
     * @param result     文件结果实体
     * @param sourceFile 来源文件实体（可为 null）
     * @return 文件结果 VO
     */
    public FileResultVO toFileResultVO(WbFileResult result, WbSourceFile sourceFile) {
        FileResultVO vo = new FileResultVO();
        vo.setId(result.getId());
        vo.setKnowledgeBaseId(result.getKnowledgeBaseId());
        vo.setSourceFileId(result.getSourceFileId());
        vo.setCreateTime(result.getCreateTime());
        if (NullUtil.isNotNull(sourceFile)) {
            vo.setFileId(sourceFile.getFileId());
            vo.setFileName(sourceFile.getFileName());
            vo.setFileSize(sourceFile.getFileSize());
        }
        return vo;
    }
}
