package com.workbuddy.worker.chunking.impl.table;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表格切片器（整表一片）：整表 Markdown 一片（表头行+分隔行+全部数据行）；
 * 总长 > maxLen 时降级为行级切片（复用 row-slice 分组逻辑，短行阈值/组大小回退 30/3），保证不产出超长片。
 */
@Component
public class TableWholeStrategy extends AbstractTableSliceStrategy {

    /** 超长降级行级的短行阈值（字符，回退口径） */
    private static final int FALLBACK_GROUP_THRESHOLD = 30;

    /** 超长降级行级的组大小（行数，回退口径） */
    private static final int FALLBACK_GROUP_SIZE = 3;

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.TABLE_WHOLE;
    }

    @Override
    protected List<Chunk> slicePrepared(TableMarkdownSupport.TablePrep prep, ViewElement element,
                                        SliceContext context) {
        int maxLen = tableRoute(context).intParam(ChunkParamKeys.MAX_LEN, 2000);
        String content = TableMarkdownSupport.markdownContent(prep.rows().rows, prep.headerByCol());
        if (content.length() <= maxLen) {
            return List.of(TableMarkdownSupport.buildChunk(content, element, prep.table(), context, prep.rows().ids));
        }
        // 超长降级行级（groupThreshold/groupSize 回退 30/3）
        return TableMarkdownSupport.rowSlice(prep.rows(), prep.headerByCol(), element, prep.table(), context,
                FALLBACK_GROUP_THRESHOLD, FALLBACK_GROUP_SIZE);
    }
}
