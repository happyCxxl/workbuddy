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
 * 表格切片器（招投标行规核心）：按行/行组切、不拆散单元格；评分项与分值同属一行不分离；
 * 跨页续表在组装环节已接续成单表，整表自然切。短行（< groupThreshold）每 groupSize 行一组，长行单行一片。
 * 片内容为合法 Markdown 表格（有表头：表头行 + 分隔行 + 数据行；拆片重复表头）。
 */
@Component
public class TableRowSliceStrategy extends AbstractTableSliceStrategy {

    /**
     * 本策略对应的切片算法。
     *
     * @return 算法枚举
     */
    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.TABLE_ROW_SLICE;
    }

    /**
     * 行级切片：短行按组切、长行单行成片，表头随片。
     *
     * @param prep    表格准备结果
     * @param element 视图元素
     * @param context 切片上下文
     * @return 切片列表
     */
    @Override
    protected List<Chunk> slicePrepared(TableMarkdownSupport.TablePrep prep, ViewElement element,
                                        SliceContext context) {
        ChunkRouteConfig tableConfig = tableRoute(context);
        int groupThreshold = tableConfig.intParam(ChunkParamKeys.GROUP_THRESHOLD, 30);
        int groupSize = tableConfig.intParam(ChunkParamKeys.GROUP_SIZE, 3);
        return TableMarkdownSupport.rowSlice(prep.rows(), prep.headerByCol(), element, prep.table(), context,
                groupThreshold, groupSize);
    }
}
