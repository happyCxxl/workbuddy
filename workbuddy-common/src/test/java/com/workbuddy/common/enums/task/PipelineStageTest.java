package com.workbuddy.common.enums.task;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水线环节单测。
 *
 * <p>守护环节名的稳定性与文件链环节集合：环节名随组合快照与步骤日志落库，
 * 是跨模块传输的词汇表，重命名即断链。
 */
class PipelineStageTest {

    /**
     * 文件链环节集合覆盖解析到向量化五个环节。
     *
     * <p>守护 {@code FILE_CHAIN_STAGES}：漏一个文件链环节即失败（冻结集口径失真）。
     */
    @Test
    void fileChainStagesShouldCoverParseToEmbed() {
        assertEquals(Set.of("PARSE", "STRUCTURE", "PREPROCESS", "CHUNK", "EMBED"),
                PipelineStage.FILE_CHAIN_STAGES);
    }

    /**
     * 每个环节都有中文名（任务详情展示）。
     *
     * <p>守护 {@code getLabel}：说明缺失即失败（前端显示裸枚举名）。
     */
    @Test
    void everyStageShouldCarryLabel() {
        for (PipelineStage stage : PipelineStage.values()) {
            assertFalse(stage.getLabel().isBlank(), () -> stage + " 缺中文名");
        }
    }

    /**
     * 建索引环节不属文件链（索引由发布动作触发，不随文件链排队）。
     *
     * <p>守护文件链与索引环节的边界：把 BUILD_INDEX 并入文件链即失败。
     * <p>守护 {@code buildIndexShouldNotBelongToFileChain}：该场景行为改动即失败。
     */
    @Test
    void buildIndexShouldNotBelongToFileChain() {
        assertTrue(PipelineStage.FILE_CHAIN_STAGES.contains("EMBED"));
        assertFalse(PipelineStage.FILE_CHAIN_STAGES.contains("BUILD_INDEX"));
    }
}
