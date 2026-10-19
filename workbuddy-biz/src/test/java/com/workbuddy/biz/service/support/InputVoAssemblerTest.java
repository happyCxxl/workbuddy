package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 文档输入 VO 组装器单测（纯映射无状态类，用真实实例断言）。
 *
 * <p>守护 {@code toSubmitLogVO}/{@code toFileResultVO}/{@code toSubmitResponse} 的逐列搬运：
 * 提交记录与文件结果列表的每一列都直接决定页面显示，漏搬即空白。
 */
class InputVoAssemblerTest {

    private final InputVoAssembler assembler = new InputVoAssembler();

    /**
     * 提交记录：实体列逐列映射到 VO。
     *
     * <p>守护 {@code toSubmitLogVO}：知识库/文件/指纹/时间任一列漏搬即失败。
     */
    @Test
    void toSubmitLogShouldCarryEveryColumn() {
        WbSubmitLog log = submitLog();

        SubmitLogVO vo = assembler.toSubmitLogVO(log);

        assertEquals(30L, vo.getId());
        assertEquals(1L, vo.getKnowledgeBaseId());
        assertEquals("req-1", vo.getRequestId());
        assertEquals("88", vo.getFileId());
        assertEquals("sha-abc", vo.getSha256());
        assertEquals("招标文件.pdf", vo.getFileName());
        assertEquals(10L, vo.getFileResultId());
        assertEquals("FAIL", vo.getStatus());
        assertEquals("FORMAT_NOT_ALLOWED", vo.getFailReason());
        assertEquals(log.getCreateTime(), vo.getCreateTime());
    }

    /**
     * 提交响应：日志 VO + 解析任务号（无任务为 null）。
     *
     * <p>守护 {@code toSubmitResponse}：任务号丢失会让页面无法轮询解析进度。
     */
    @Test
    void toSubmitResponseShouldCarryLogAndTaskId() {
        WbSubmitLog log = submitLog();

        FileSubmitVO withTask = assembler.toSubmitResponse(log, 20L);
        FileSubmitVO withoutTask = assembler.toSubmitResponse(log, null);

        assertEquals(30L, withTask.getSubmitLog().getId());
        assertEquals(20L, withTask.getPipelineTaskId());
        assertNull(withoutTask.getPipelineTaskId());
    }

    /**
     * 文件结果：实体列与来源文件列合并下发，来源文件缺失时来源列留空。
     *
     * <p>守护 {@code toFileResultVO}：来源文件列漏搬或缺失时伪造都算错。
     */
    @Test
    void toFileResultShouldMergeSourceFileColumns() {
        WbFileResult result = new WbFileResult();
        result.setId(10L);
        result.setKnowledgeBaseId(1L);
        result.setSourceFileId(7L);
        result.setCreateTime(LocalDateTime.of(2026, 10, 9, 10, 0));
        WbSourceFile source = new WbSourceFile();
        source.setId(7L);
        source.setFileId("88");
        source.setFileName("招标文件.pdf");
        source.setFileSize(100L);

        FileResultVO withSource = assembler.toFileResultVO(result, source);
        FileResultVO withoutSource = assembler.toFileResultVO(result, null);

        assertEquals(10L, withSource.getId());
        assertEquals(1L, withSource.getKnowledgeBaseId());
        assertEquals(7L, withSource.getSourceFileId());
        assertEquals("88", withSource.getFileId());
        assertEquals("招标文件.pdf", withSource.getFileName());
        assertEquals(100L, withSource.getFileSize());
        assertEquals(result.getCreateTime(), withSource.getCreateTime());
        // 来源文件缺失：结果列照发，来源列留空
        assertEquals(10L, withoutSource.getId());
        assertNull(withoutSource.getFileId());
        assertNull(withoutSource.getFileName());
        assertNull(withoutSource.getFileSize());
    }

    /** 提交日志夹具：所有列都有值 */
    private WbSubmitLog submitLog() {
        WbSubmitLog log = new WbSubmitLog();
        log.setId(30L);
        log.setKnowledgeBaseId(1L);
        log.setRequestId("req-1");
        log.setFileId("88");
        log.setSha256("sha-abc");
        log.setFileName("招标文件.pdf");
        log.setFileResultId(10L);
        log.setStatus("FAIL");
        log.setFailReason("FORMAT_NOT_ALLOWED");
        log.setCreateTime(LocalDateTime.of(2026, 10, 9, 10, 0));
        return log;
    }
}
