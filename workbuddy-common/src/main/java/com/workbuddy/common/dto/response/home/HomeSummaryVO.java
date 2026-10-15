package com.workbuddy.common.dto.response.home;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 首页资产速览视图对象：每类资产都是总数 + 细分计数。
 *
 * <p>**知识库与文档两类计数按可见范围过滤**（普通用户只数自己创建的库及其下文档，
 * 管理员数全部），与知识库列表页同一口径；策略版本数是平台级资产，不按归属过滤。
 *
 * @author cxxl
 */
@Data
public class HomeSummaryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 可见范围内知识库总数（含已停用） */
    private Long knowledgeBaseCount;

    /** 其中启用中的知识库数 */
    private Long enabledKnowledgeBaseCount;

    /** 可用策略版本数（四类合计，**只含启用中的**，与界面「可用策略」文案一致） */
    private Long strategyVersionCount;

    /** 预处理策略版本数 */
    private Long preprocessVersionCount;

    /** 切片策略版本数 */
    private Long chunkVersionCount;

    /** 向量化策略版本数 */
    private Long embedVersionCount;

    /** 检索策略版本数 */
    private Long retrievalVersionCount;

    /** 文档数：可见库下的 wb_file_result 行数（已建档文档，**不含文件校验失败**） */
    private Long documentCount;
}
