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
 * CID 映射失败降级处置：写 CID_MAPPING_FAILED_PAGE 告警 + 登记失败页，计 1 个失败单元。
 */
@Component
public class CidMappingFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.CID_MAPPING_FAILED;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.CID_MAPPING_FAILED_PAGE, null, "WARN",
                region + " 字体缺 Unicode 映射，计失败页"));
        FallbackSupport.addFailedPage(quality, FallbackSupport.parsePage(region));
        return 1;
    }
}
