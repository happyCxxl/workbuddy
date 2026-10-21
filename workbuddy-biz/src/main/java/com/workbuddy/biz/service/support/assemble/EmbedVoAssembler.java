package com.workbuddy.biz.service.support.assemble;

import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.dto.response.embed.EmbedRecordItemVO;
import org.mapstruct.Mapper;

import java.util.List;

/**
 * 向量化记录 VO 映射：记录行 → 记录条目视图（编译期生成实现，运行期无反射）。
 *
 * <p>只做字段搬运；集合摘要等统计口径在统计支撑类，不在本映射。
 */
@Mapper(componentModel = "spring")
public interface EmbedVoAssembler {

    /**
     * 记录行转记录条目 VO 列表。
     *
     * @param records 向量记录列表
     * @return 记录条目 VO 列表
     */
    List<EmbedRecordItemVO> toRecordItemVOs(List<WbEmbeddingRecord> records);

    /**
     * 单条记录行转记录条目 VO。
     *
     * @param record 向量记录行
     * @return 记录条目 VO
     */
    EmbedRecordItemVO toRecordItemVO(WbEmbeddingRecord record);
}
