package com.workbuddy.worker.structure;

import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.structure.AssembleOutcome;

/**
 * 文档组装器：把解析环节多路结果焊接成唯一 UnifiedDocument（固定环节，无策略配置）。
 * 多道工艺按序执行（标准化/去重合并/阅读顺序/结构组装/跨页接续/溯源校验/重复噪声识别）；biz 只依赖本接口。
 *
 * @author cxxl
 */
public interface DocumentAssemblerPort {

    /**
     * 执行组装。
     *
     * @param parseResult 解析环节结果封装
     * @param context     组装上下文
     * @return 组装产出（统一文档 + 报告 + 门槛建议 + 子步骤记录）
     */
    AssembleOutcome assemble(ParseResult parseResult, AssembleContext context);
}
