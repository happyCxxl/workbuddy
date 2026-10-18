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
 * 嵌入图片降级处置：结构缺口——嵌入图片仅引用，写告警不判失败（不计失败单元）。
 */
@Component
public class ImageEmbeddedFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.IMAGE_EMBEDDED;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.IMAGE_TEXT_UNRECOGNIZED, null, "WARN",
                region + " 嵌入图片文字未识别（OCR 暂未接入，仅记录引用与图注）"));
        return 0;
    }
}
