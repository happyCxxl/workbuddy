package com.workbuddy.biz.service.support.stagecontent;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.convert.knowledge.StructureConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 切片环节内容：按该次运行产物 artifactId 精确取切片集合，过滤与分页都下推到 wb_knowledge_chunk。
 */
@Component
@RequiredArgsConstructor
public class ChunkStageContentSupport {

    private final StructureConverter knowledgeConverter;

    /**
     * 切片分页内容：过滤（内容类型 / 是否兜底 / 有无父片）与分页都下推 DB，条目按集合内顺序给出对齐键。
     *
     * <p>total 取过滤后的总条数，与 items 同一口径（先过滤再分页）；集合缺失时保留空壳。
     *
     * @param vo               产物内容视图（回填 items 与 total）
     * @param ref              切片产物对象位置
     * @param filter           切片内容过滤（可空）
     * @param pageNo           页码（从 1 起）
     * @param pageSize         每页条数
     * @param chunkSetDbService 切片集合数据访问
     * @param chunkDbService   切片数据访问
     */
    public void fillPage(StageContentVO vo, ObjectRef ref, ChunkContentFilterDTO filter,
                                int pageNo, int pageSize, WbChunkSetDbService chunkSetDbService,
                                WbChunkDbService chunkDbService) {
        WbChunkSet chunkSet = chunkSetDbService.getByArtifactId(ref.objectKey());
        if (NullUtil.isNull(chunkSet)) {
            return;
        }
        IPage<WbChunk> chunkPage = chunkDbService.pageByChunkSetId(chunkSet.getId(), filter, pageNo, pageSize);
        List<StageContentItemVO> items = new ArrayList<>();
        for (WbChunk chunk : chunkPage.getRecords()) {
            items.add(chunkItemOf(chunk));
        }
        vo.setTotal((int) chunkPage.getTotal());
        vo.setItems(items);
    }

    /** 切片条目：对齐键取切片 ID（与详情侧的片同键，两侧互相定位） */
    private StageContentItemVO chunkItemOf(WbChunk chunk) {
        StageContentItemVO item = knowledgeConverter.itemOf(0, chunk.getContentType(), null,
                chunk.getContent(), chunkExtra(chunk));
        item.setSeq(ObjectUtil.defaultIfNull(chunk.getOrderNo(), 0));
        item.setAlignKey(StrUtil.blankToDefault(chunk.getChunkId(), String.valueOf(item.getSeq())));
        return item;
    }

    /** 切片环节专属字段（来源元素个数与兜底原因只在切片产物里，不进这里） */
    private StageContentExtraVO chunkExtra(WbChunk chunk) {
        StageContentExtraVO extra = new StageContentExtraVO();
        extra.setChunkId(chunk.getChunkId());
        extra.setContentType(chunk.getContentType());
        extra.setTitlePath(chunk.getTitlePath());
        extra.setCharCount(chunk.getCharCount());
        extra.setTokenCount(chunk.getTokenCount());
        extra.setOrderNo(chunk.getOrderNo());
        extra.setParentChunkId(chunk.getParentChunkId());
        extra.setPageRange(chunk.getPageRange());
        extra.setTableRef(chunk.getTableRef());
        return extra;
    }
}
