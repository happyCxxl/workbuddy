package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文档输入服务单测夹具：提交请求、知识库、校验结果、元数据与建档三写桩。
 */
abstract class KnowledgeBaseFileTestFixture extends KnowledgeBaseTestFixture {

    /** 档案所在的数据源 */
    protected static final Long SOURCE_ID = 1L;

    /** 当前启用的数据源 */
    protected static final Long CURRENT_SOURCE_ID = 2L;

    /**
     * 提交请求夹具（fileId=88 / requestId=req-1）。
     *
     * @return 提交请求
     */
    protected FileSubmitDTO request() {
        FileSubmitDTO request = new FileSubmitDTO();
        request.setFileId("88");
        request.setRequestId("req-1");
        return request;
    }

    /**
     * 启用中的知识库夹具（ID=1，归当前用户）。
     *
     * @return 知识库实体
     */
    protected WbKnowledgeBase activeKb() {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(1L);
        kb.setStatus(KnowledgeBaseStatus.ACTIVE.getCode());
        kb.setUserId(ME);
        return kb;
    }

    /**
     * 校验通过的 PDF 文件结果夹具。
     *
     * @return 文件校验结果
     */
    protected FileValidationResult passResult() {
        return FileValidationResult.pass(FileFormat.PDF, "application/pdf", 100L, "sha256-abc");
    }

    /**
     * 文件元数据夹具。
     *
     * @return 文件元数据
     */
    protected FileMetadata metadata() {
        FileMetadata metadata = new FileMetadata();
        metadata.setFileName("招标文件.pdf");
        metadata.setFileSize(100L);
        return metadata;
    }

    /**
     * 建档三写成功桩：来源文件 / 文件结果 / 提交日志回填自增 ID。
     *
     * @param sourceFileDbService 来源文件数据访问替身
     * @param fileResultDbService 文件结果数据访问替身
     * @param submitLogDbService  提交日志数据访问替身
     */
    protected void stubSuccessWrites(WbSourceFileDbService sourceFileDbService,
                                      WbFileResultDbService fileResultDbService,
                                      WbSubmitLogDbService submitLogDbService) {
        when(sourceFileDbService.findByFileId("88")).thenReturn(null);
        when(sourceFileDbService.save(any(WbSourceFile.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSourceFile.class).setId(1L);
            return true;
        });
        when(fileResultDbService.save(any(WbFileResult.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbFileResult.class).setId(10L);
            return true;
        });
        when(submitLogDbService.save(any(WbSubmitLog.class))).thenAnswer(inv -> {
            inv.getArgument(0, WbSubmitLog.class).setId(30L);
            return true;
        });
    }

    /**
     * 文件结果分页查询条件夹具（环节可空）。
     *
     * @param stage 环节（可空）
     * @return 查询条件
     */
    protected FileResultQueryDTO fileResultQuery(String stage) {
        FileResultQueryDTO query = new FileResultQueryDTO();
        query.setStage(stage);
        return query;
    }

    /**
     * 五个环节的空任务查询桩（列表行没有任何任务）。
     *
     * @param pipelineTaskDbService 管线任务数据访问替身
     * @param fileResultIds        文件结果 ID 列表
     */
    protected void stubNoStageTasks(WbPipelineTaskDbService pipelineTaskDbService, List<Long> fileResultIds) {
        for (String statusStage : List.of("PARSE", "STRUCTURE", "PREPROCESS", "CHUNK", "EMBED")) {
            when(pipelineTaskDbService.listByFileResultIdsAndStage(fileResultIds, statusStage))
                    .thenReturn(List.of());
        }
    }
}
