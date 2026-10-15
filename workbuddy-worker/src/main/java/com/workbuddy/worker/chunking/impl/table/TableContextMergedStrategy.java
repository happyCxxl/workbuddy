package com.workbuddy.worker.chunking.impl.table;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkAlgorithm;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.strategy.ChunkParamKeys;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 表格切片器（表+引导段落）：基于行级切片（groupThreshold/groupSize 同 row-slice），每片 content 首行前
 * 拼接前导文本段落（该表前最近一个非空 BODY 元素，截断 leadMaxLen，超长加省略号）。
 * 前导段由管线维护在 SliceContext.leadParagraph。与流程层 tableInBodyFlow 互斥（保存校验 40001）。
 *
 * @author cxxl
 */
@Component
public class TableContextMergedStrategy extends AbstractTableSliceStrategy {

    @Override
    public ChunkAlgorithm algorithm() {
        return ChunkAlgorithm.TABLE_CONTEXT_MERGED;
    }

    @Override
    protected List<Chunk> slicePrepared(TableMarkdownSupport.TablePrep prep, ViewElement element,
                                        SliceContext context) {
        ChunkRouteConfig tableConfig = tableRoute(context);
        int leadMaxLen = tableConfig.intParam(ChunkParamKeys.LEAD_MAX_LEN, 200);
        int groupThreshold = tableConfig.intParam(ChunkParamKeys.GROUP_THRESHOLD, 30);
        int groupSize = tableConfig.intParam(ChunkParamKeys.GROUP_SIZE, 3);
        List<Chunk> chunks = TableMarkdownSupport.rowSlice(prep.rows(), prep.headerByCol(), element, prep.table(),
                context, groupThreshold, groupSize);

        String lead = context.getLeadParagraph();
        if (StrUtil.isNotBlank(lead)) {
            String prefix = lead.length() > leadMaxLen ? lead.substring(0, leadMaxLen) + "…" : lead;
            for (Chunk chunk : chunks) {
                chunk.setContent(prefix + "\n" + chunk.getContent());
                chunk.setCharCount(chunk.getContent().length());
            }
        }
        return chunks;
    }
}
