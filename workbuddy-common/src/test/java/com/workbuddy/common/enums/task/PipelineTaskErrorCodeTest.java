package com.workbuddy.common.enums.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 流水线错误码单测。
 *
 * <p>守护错误码词汇表的完整性：错误码随任务行落库并回传前端，环节失败分类
 * 依赖这些常量存在；重命名或删除即历史任务无法解读。
 */
class PipelineTaskErrorCodeTest {

    /**
     * 每个文件链环节都有「失败」与「空输入」两类错误码。
     *
     * <p>守护 {@code PipelineTaskErrorCode} 的环节失败分类词汇表：某环节缺失败码即失败（该环节失败无处安放）。
     */
    @Test
    void everyFileChainStageShouldHaveFailureCodes() {
        String[] stages = {"PARSE", "STRUCTURE", "PREPROCESS", "CHUNK", "EMBED"};
        for (String stage : stages) {
            assertNotNull(PipelineTaskErrorCode.valueOf(stage + "_FAILED"), stage + " 缺 _FAILED 错误码");
        }
        String[] emptyStages = {"STRUCTURE", "PREPROCESS", "CHUNK", "EMBED"};
        for (String stage : emptyStages) {
            assertNotNull(PipelineTaskErrorCode.valueOf(stage + "_EMPTY"), stage + " 缺 _EMPTY 错误码");
        }
    }

    /**
     * 基础设施类错误码齐备（入队、超时、存储不一致与质量门槛）。
     *
     * <p>守护 {@code PipelineTaskErrorCode} 的框架错误码：删掉任一即失败（相应失败路径无码可用）。
     */
    @Test
    void infrastructureErrorCodesShouldExist() {
        assertNotNull(PipelineTaskErrorCode.TASK_ENQUEUE_FAILED);
        assertNotNull(PipelineTaskErrorCode.TASK_UPSTREAM_MISSING);
        assertNotNull(PipelineTaskErrorCode.EXECUTOR_TIMEOUT);
        assertNotNull(PipelineTaskErrorCode.TASK_SHUTDOWN_INTERRUPTED);
        assertNotNull(PipelineTaskErrorCode.TASK_STORAGE_MISMATCH);
        assertNotNull(PipelineTaskErrorCode.PARSE_CORRUPTED);
        assertNotNull(PipelineTaskErrorCode.SCANNED_UNSUPPORTED);
        assertNotNull(PipelineTaskErrorCode.RATIO_BELOW_THRESHOLD);
        assertNotNull(PipelineTaskErrorCode.EMBED_MODEL_INCOMPATIBLE);
        assertNotNull(PipelineTaskErrorCode.EMBED_CONSISTENCY_FAILED);
        assertNotNull(PipelineTaskErrorCode.INDEX_BUILD_FAILED);
    }
}
