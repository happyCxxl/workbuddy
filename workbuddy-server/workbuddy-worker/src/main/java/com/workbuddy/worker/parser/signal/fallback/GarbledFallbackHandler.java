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
 * 乱码页降级处置：写 GARBLED_PAGE 告警 + 登记失败页，计 1 个失败单元。
 */
@Component
public class GarbledFallbackHandler implements SignalFallbackHandler {

    /**
     * 处理的信号子类型。
     *
     * @return 子类型枚举
     */
    @Override
    public SignalSubtype subtype() {
        return SignalSubtype.GARBLED;
    }

    /**
     * 文本乱码：登记乱码告警并标记该页需降级处理。
     *
     * @param signal  降级信号
     * @param quality 质量信息（追加告警与标记）
     * @return 计入失败单元的条数
     */
    @Override
    public int handle(Signal signal, QualityInfo quality) {
        String region = StrUtil.blankToDefault(signal.getRegion(), "");
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.GARBLED_PAGE, null, "WARN",
                region + " 乱码率超阈值，计失败页"));
        FallbackSupport.addFailedPage(quality, FallbackSupport.parsePage(region));
        return 1;
    }
}
