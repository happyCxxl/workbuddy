package com.workbuddy.common.domain.task;

import com.workbuddy.common.dto.response.task.StepLogVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 子步骤字段契约单测。
 *
 * <p>守护 {@code copyFrom} 的逐字段搬运：实体 / 领域对象 / VO 同构十一字段，
 * 漏搬一列会让执行树少一列信息（耗时、命中数、错误等）。
 */
class StepLogFieldsTest {

    /**
     * 十一字段逐一搬运，不多不少。
     *
     * <p>守护 {@code copyFrom}：任一列漏搬即失败。
     */
    @Test
    void copyFromShouldCopyAllElevenFields() {
        StepLogVO source = new StepLogVO();
        source.setStepName("切分");
        source.setStatus("SUCCESS");
        source.setCapabilityVersion("chunk-hybrid-v1");
        source.setStartedAt(LocalDateTime.of(2026, 10, 9, 10, 0));
        source.setFinishedAt(LocalDateTime.of(2026, 10, 9, 10, 1));
        source.setDuration(60);
        source.setWarningCount(1);
        source.setMatchedCount(5);
        source.setChangedCount(2);
        source.setAvgLen(400);
        source.setError(null);
        StepLogVO target = new StepLogVO();

        target.copyFrom(source);

        assertEquals("切分", target.getStepName());
        assertEquals("SUCCESS", target.getStatus());
        assertEquals("chunk-hybrid-v1", target.getCapabilityVersion());
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 0), target.getStartedAt());
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 1), target.getFinishedAt());
        assertEquals(60, target.getDuration());
        assertEquals(1, target.getWarningCount());
        assertEquals(5, target.getMatchedCount());
        assertEquals(2, target.getChangedCount());
        assertEquals(400, target.getAvgLen());
        assertEquals(null, target.getError());
    }
}
