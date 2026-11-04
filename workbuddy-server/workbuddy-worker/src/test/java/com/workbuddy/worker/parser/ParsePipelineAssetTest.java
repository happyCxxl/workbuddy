package com.workbuddy.worker.parser;

import com.workbuddy.common.domain.parse.ParseOutcome;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.worker.testkit.RecordingAssetSink;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 解析管线资产：解析器写入的资产随 ParseResult 一起落产物。
 */
class ParsePipelineAssetTest {

    /** 解析器向 assetSink 写入的资产应出现在 ParseResult.assets。 */
    @Test
    void resultShouldCarryAssetsWrittenByParser() {
        ParsePipelineTestFixture fixture = new ParsePipelineTestFixture();
        RecordingAssetSink sink = new RecordingAssetSink();
        ParseContext context = fixture.context("application/pdf");
        context.setAssetSink(sink);

        DocumentParserPort parser = new DocumentParserPort() {
            @Override
            public boolean supports(String mimeType) {
                return "application/pdf".equals(mimeType);
            }

            @Override
            public ParseSource parse(ParseContext parseContext) {
                sink.put(ParseAssetKind.PAGE_IMAGE, new byte[]{1, 2, 3}, "image/png",
                        1, null, 10, 10);
                ParseSource source = ParseSource.nativeSource("fake-1.0");
                source.setUnitCount(1);
                source.setPageMetrics(List.of(fixture.normalPage(1)));
                return source;
            }

            @Override
            public String capabilityName() {
                return "fake";
            }

            @Override
            public String capabilityVersion() {
                return "1.0";
            }
        };

        ParseOutcome outcome = fixture.pipeline(parser).run(context);

        assertEquals(1, outcome.getParseResult().getAssets().size());
        assertEquals(ParseAssetKind.PAGE_IMAGE, outcome.getParseResult().getAssets().get(0).getKind());
        assertEquals(1, outcome.getParseResult().getSummary().getAssetCount());
    }
}
