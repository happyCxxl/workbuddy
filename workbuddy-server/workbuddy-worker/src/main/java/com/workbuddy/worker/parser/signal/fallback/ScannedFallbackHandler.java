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
 * 扫描页降级处置：写 SCANNED_PAGE 告警并登记扫描页；是否计失败由质量门槛结合 OCR 覆盖统一裁决。
 */
@Component
public class ScannedFallbackHandler implements SignalFallbackHandler {

    /**
     * 处理的信号子类型。
     *
     * @return 子类型枚举
     */
    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.SCANNED;
    }

    /**
     * 扫描件：登记告警与扫描页；失败判定留给质量门槛（OCR 覆盖的页可恢复）。
     *
     * @param signal  降级信号
     * @param quality 质量信息（追加告警与标记）
     * @return 计入失败单元的条数（固定 0，由门禁统一裁决）
     */
    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        Integer page = FallbackSupport.parsePage(region);
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.SCANNED_PAGE, null, "WARN",
                region + " 无文本层（扫描页）"));
        FallbackSupport.addScannedPage(quality, page);
        return 0;
    }
}
