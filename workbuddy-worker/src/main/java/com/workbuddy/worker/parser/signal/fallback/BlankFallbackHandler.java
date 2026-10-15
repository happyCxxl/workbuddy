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
 * 空白页降级处置：写 BLANK_PAGE 告警 + 登记空白页，不计失败单元
 * （无文本也无图片，既不能算成功也不该算失败）。
 *
 * @author cxxl
 */
@Component
public class BlankFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.BLANK;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.BLANK_PAGE, null, "WARN",
                region + " 无文本也无图片，按空白页处理"));
        FallbackSupport.addBlankPage(quality, FallbackSupport.parsePage(region));
        return 0;
    }
}
