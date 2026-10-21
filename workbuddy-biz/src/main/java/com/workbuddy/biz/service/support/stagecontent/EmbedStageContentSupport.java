package com.workbuddy.biz.service.support.stagecontent;

import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.convert.KnowledgeConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化环节内容：按该次运行产物 artifactId 精确取向量集合，记录按集合内顺序编号。
 */
@Component
@RequiredArgsConstructor
public class EmbedStageContentSupport {

    private final KnowledgeConverter knowledgeConverter;

    /**
     * 向量化记录 → 内容条目：顺序号从 1 起，对齐键与顺序号同值。
     *
     * @param embeddingSetDbService    向量集合数据访问
     * @param embeddingRecordDbService 向量记录数据访问
     * @param ref                      向量化产物对象位置
     * @return 内容条目；向量集合缺失时为空列表
     */
    public List<StageContentItemVO> items(WbEmbeddingSetDbService embeddingSetDbService,
                                                 WbEmbeddingRecordDbService embeddingRecordDbService,
                                                 ObjectRef ref) {
        WbEmbeddingSet embeddingSet = embeddingSetDbService.getByArtifactId(ref.objectKey());
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(embeddingSet)) {
            return items;
        }
        int seq = 1;
        for (WbEmbeddingRecord record : embeddingRecordDbService.listByEmbeddingSetId(embeddingSet.getId())) {
            items.add(knowledgeConverter.itemOf(seq, record.getContentType(), record.getStatus(),
                    record.getInputText(), embedExtra(record)));
            seq++;
        }
        return items;
    }

    /** 向量化环节专属字段 */
    private StageContentExtraVO embedExtra(WbEmbeddingRecord record) {
        StageContentExtraVO extra = new StageContentExtraVO();
        extra.setChunkId(record.getChunkId());
        extra.setTokenCount(record.getTokenCount());
        extra.setCacheHit(record.getCacheHit());
        extra.setRequestId(record.getRequestId());
        return extra;
    }
}
