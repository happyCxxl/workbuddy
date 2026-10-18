package com.workbuddy.vector;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 向量检索命中行：CollectionRow + 相似度分数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ScoredRow extends CollectionRow {

    /** 相似度分数（COSINE；可能为 null） */
    private Float score;
}
