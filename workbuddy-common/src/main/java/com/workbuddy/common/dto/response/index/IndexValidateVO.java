package com.workbuddy.common.dto.response.index;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 索引版本验证结果：
 * 一致性自检（本版本 chunkId 集合 vs 组合产物集合）+ 抽样检索冒烟（向量 1 次 + 全文 1 次）。
 *
 * @author cxxl
 */
@Data
public class IndexValidateVO {

    /** 整体是否通过（全部子项通过） */
    private boolean passed;

    /** 子项结果 */
    private List<IndexValidateItemVO> items = new ArrayList<>();
}
