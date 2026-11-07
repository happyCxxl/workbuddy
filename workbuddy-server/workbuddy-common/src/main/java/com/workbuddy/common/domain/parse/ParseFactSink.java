package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;

import java.util.List;

/**
 * 解析事实与能力使用记录的收集端：全档解析结果与页局部工作区都实现该契约。
 * 页内步骤只写页局部收集器，跨页合并由调用方按页序完成，避免多页并发时争用同一列表。
 */
public interface ParseFactSink {

    /**
     * 事实信号收集列表。
     *
     * @return 可变的事实列表
     */
    List<ParseFact> getFacts();

    /**
     * 能力使用记录收集列表。
     *
     * @return 可变的能力列表
     */
    List<CapabilityUsage> getCapabilities();
}
