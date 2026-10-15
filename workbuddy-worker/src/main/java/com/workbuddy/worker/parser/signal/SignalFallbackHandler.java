package com.workbuddy.worker.parser.signal;

import com.workbuddy.common.domain.parse.QualityInfo;
import com.workbuddy.common.domain.parse.signal.Signal;
import com.workbuddy.common.enums.parse.SignalSubtype;

/**
 * 信号降级处置策略（责任链）：每个 SignalSubtype 一个实现，管线按 subtype 查找执行。
 * 内置降级口径：写质量告警 + 登记失败/扫描页；返回该信号计入的失败单元数（0 或 1）。
 * 新增信号子类型 = 新增实现类（管线零改动）；接入能力（OCR 等）后替换对应实现即可。
 *
 * @author cxxl
 */
public interface SignalFallbackHandler {

    /** 处置的信号子类型 */
    SignalSubtype subtype();

    /**
     * 执行内置降级处置（不依赖 evidence 文案，按子类型分支）。
     *
     * @param signal  信号（region 携带页码等定位）
     * @param quality 质量信息（告警与失败/扫描页登记落点）
     * @return 该信号计入的失败单元数
     */
    int handle(Signal signal, QualityInfo quality);
}
