package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;

import java.util.List;

/**
 * 全档页面装配结果：路级事实与能力（元素已回填进单元）。
 *
 * @param facts 全档事实
 * @param capabilities 全档能力使用
 */
public record PageAssemblyResult(List<ParseFact> facts, List<CapabilityUsage> capabilities) {
}
