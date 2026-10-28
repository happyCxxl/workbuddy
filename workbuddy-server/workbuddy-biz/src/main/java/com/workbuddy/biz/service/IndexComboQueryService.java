package com.workbuddy.biz.service;

import com.workbuddy.common.dto.response.index.IndexComboVO;

import java.util.List;

/**
 * 索引组合查询用例：按范围内文件枚举可构建组合（成员子集口径，构建弹窗数据源）。
 */
public interface IndexComboQueryService {

    /**
     * 列出可构建的索引组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param fileResultIds   限定参与的文件结果 ID（可空）
     * @return 组合列表；范围内无文件时为空列表
     */
    List<IndexComboVO> listCombos(Long knowledgeBaseId, List<Long> fileResultIds);
}
