package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;

import java.util.List;

/**
 * 全档页面装配结果：元素已按页序合并，事实与能力保持产生顺序。
 *
 * @param elements 全档元素
 * @param facts 全档事实
 * @param capabilities 全档能力使用
 */
public record PageAssemblyResult(List<ParseElement> elements, List<ParseFact> facts,
                                 List<CapabilityUsage> capabilities) {
}
