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
 * 扫描页降级处置：写 SCANNED_PAGE 告警 + 登记扫描页与失败页，计 1 个失败单元。
 * OCR 能力接入后由能力调用替换本处置（扩展点）。
 *
 * @author cxxl
 */
@Component
public class ScannedFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.SCANNED;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        Integer page = FallbackSupport.parsePage(region);
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.SCANNED_PAGE, null, "WARN",
                region + " 无文本层（扫描页），OCR 暂未支持"));
        FallbackSupport.addScannedPage(quality, page);
        FallbackSupport.addFailedPage(quality, page);
        return 1;
    }
}
