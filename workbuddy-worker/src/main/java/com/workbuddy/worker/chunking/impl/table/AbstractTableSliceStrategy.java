package com.workbuddy.worker.chunking.impl.table;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.chunk.ChunkRoute;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.SliceContext;
import com.workbuddy.worker.chunking.slice.SliceStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkRouteConfig;

import java.util.List;

/**
 * 表格切片器骨架：先取"结构表 + 表头映射 + 数据行"，取不到（元素或单元格缺失）返回空片，
 * 再交给子类按已备好的表格切分。
 */
public abstract class AbstractTableSliceStrategy implements SliceStrategy {

    @Override
    public final List<Chunk> slice(ViewElement element, SliceContext context) {
        TableMarkdownSupport.TablePrep prep = TableMarkdownSupport.prepare(element, context);
        if (NullUtil.isNull(prep)) {
            return List.of();
        }
        return slicePrepared(prep, element, context);
    }

    /**
     * 按已备好的表格切分（prep 非空：结构表、表头映射、数据行均可用）。
     *
     * @param prep    表格准备结果
     * @param element 视图元素（检索文本口径）
     * @param context 切片上下文（含策略路由与标题路径）
     */
    protected abstract List<Chunk> slicePrepared(TableMarkdownSupport.TablePrep prep,
                                                 ViewElement element, SliceContext context);

    /** 表格路由参数（TABLE 路，解析时已补默认值） */
    protected final ChunkRouteConfig tableRoute(SliceContext context) {
        return context.getStrategy().route(ChunkRoute.TABLE);
    }
}
