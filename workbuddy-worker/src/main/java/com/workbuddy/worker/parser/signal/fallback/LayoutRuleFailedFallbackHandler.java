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
 * 版面规则失败降级处置：按顺序输出（写告警，不计失败单元）。
 * 预留：当前没有事实产出方，版面能力接入后由 provider 产 LAYOUT 事实才会走到本分支。
 */
@Component
public class LayoutRuleFailedFallbackHandler implements SignalFallbackHandler {

    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.LAYOUT_RULE_FAILED;
    }

    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.LAYOUT_RULE_FALLBACK, null, "WARN",
                region + " 版面规则失败，按顺序输出"));
        return 0;
    }
}
