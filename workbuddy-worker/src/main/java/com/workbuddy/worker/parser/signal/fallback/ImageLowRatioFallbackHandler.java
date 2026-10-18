package com.workbuddy.worker.parser.signal.fallback;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.enums.parse.SignalSubtype;
import com.workbuddy.worker.parser.signal.SignalFallbackHandler;
import org.springframework.stereotype.Component;

/**
 * 疑似图片页降级处置：解析已产出内容，只告警不计失败单元
 * （真正"整页一张图、无文本"的页由扫描页规则覆盖计失败）。
 */
@Component
public class ImageLowRatioFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.IMAGE_LOW_RATIO;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.IMAGE_PAGE_SUSPECTED, null, "WARN",
                region + " 文字占比低于阈值，疑似图片页（仅告警，OCR 预留）"));
        return 0;
    }
}
