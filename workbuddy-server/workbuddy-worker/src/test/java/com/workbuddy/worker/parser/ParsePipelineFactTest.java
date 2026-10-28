package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.CapabilitySnapshot;
import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 解析管线事实与快照单测：失败事实转告警不改变状态、能力引用回填快照。
 */
class ParsePipelineFactTest {

    private final ParsePipelineTestFixture fixture = new ParsePipelineTestFixture();

    /**
     * 守护 {@code tableFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void tableFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.TABLE);
        fact.setRegion("page 1");
        fact.setEvidence("列对齐聚类失败（疑似无边框/复杂表格）");
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals(1, outcome.getParseResult().getQuality().getWarnings().size());
        assertEquals("TABLE_RULE_FALLBACK", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code layoutFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void layoutFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.LAYOUT);
        fact.setRegion("page 1");
        fact.setEvidence("版面规则失败");
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals("LAYOUT_RULE_FALLBACK", outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code embeddedImageFactShouldWarnWithoutFailing}：该场景行为改动即失败。
     */
    @Test
    void embeddedImageFactShouldWarnWithoutFailing() {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.OCR_IMAGE);
        fact.setRegion("图 1");
        fact.setEvidence("-");
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getFailedUnits());
        assertEquals("IMAGE_TEXT_UNRECOGNIZED",
                outcome.getParseResult().getQuality().getWarnings().getFirst().getCode());
    }

    /**
     * 守护 {@code factWithoutTypeShouldBeIgnored}：该场景行为改动即失败。
     */
    @Test
    void factWithoutTypeShouldBeIgnored() {
        ParseFact fact = new ParseFact();
        fact.setRegion("page 1");
        fact.setEvidence("未标注类型的事实");
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.normalPage(1)), List.of(fact)));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        assertEquals(PipelineTaskStatus.SUCCESS, outcome.getSuggestedStatus());
        assertEquals(0, outcome.getParseResult().getQuality().getWarnings().size());
    }

    /** 能力快照回填：本路用到的模型名与版本写进快照对应项，未用到的类别保持 null（{@code capabilityUsageShouldBeFilledIntoSnapshot}）。 */
    @Test
    void capabilityUsageShouldBeFilledIntoSnapshot() {
        ParsePipeline pipeline = fixture.pipeline(fixture.fakeParser(List.of(fixture.normalPage(1)), List.of(),
                List.of(CapabilityUsage.table("stub-table", "9.9"))));

        ParseOutcome outcome = pipeline.run(fixture.context("application/pdf"));

        CapabilitySnapshot snapshot = outcome.getParseResult().getCapabilitySnapshot();
        assertEquals("stub-table", snapshot.getTable().getModel());
        assertEquals("9.9", snapshot.getTable().getVersion());
        assertNull(snapshot.getOcr());
        assertNull(snapshot.getLayout());
        assertEquals("fake", snapshot.getParserName());
    }
}
