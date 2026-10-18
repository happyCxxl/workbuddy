package com.workbuddy.worker.chunking.impl.table;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表格切片器（行组）：数据行依次入组，行数 ≥ groupSize 或组内字符累计 ≥ maxLen 即结算成片；
 * 每片合法 Markdown 表格（表头随片）。中间粒度：比行级切片更大、比整表更细。
 */
@Component
public class TableRowGroupStrategy extends AbstractTableSliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.TABLE_ROW_GROUP;
    }

    @Override
    protected List<Chunk> slicePrepared(TableMarkdownSupport.TablePrep prep, ViewElement element,
                                        SliceContext context) {
        ChunkRouteConfig tableConfig = tableRoute(context);
        int groupSize = tableConfig.intParam(ChunkParamKeys.GROUP_SIZE, 5);
        int maxLen = tableConfig.intParam(ChunkParamKeys.MAX_LEN, 600);
        return TableMarkdownSupport.rowGroupSlice(prep.rows(), prep.headerByCol(), element, prep.table(), context,
                groupSize, maxLen);
    }
}
