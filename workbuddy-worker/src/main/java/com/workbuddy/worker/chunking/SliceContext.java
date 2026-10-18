package com.workbuddy.worker.chunking;

import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.worker.chunking.slice.FallbackSlicer;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 切片器上下文（Context Object）：一次运行的全部共享状态，策略实现保持无状态。
 * 参数一律从 strategy 的路由配置/流程层读取（解析时已补默认值），不再持有全局 ChunkProperties。
 */
@Data
public class SliceContext {

    /** 预处理视图（只读） */
    private PreprocessView view;

    /** 统一文档（只读，结构参照） */
    private UnifiedDocument document;

    /** 策略快照（含补全后的路由参数与流程层设置） */
    private ChunkStrategy strategy;

    /** 兜底切片器（由管线按 fallback.algorithm 解析后注入） */
    private FallbackSlicer fallback;

    /** 元素 ID → 结构元素索引（图注/标题层级/表格行取用） */
    private Map<String, UnifiedElement> byId;

    /** 当前章节路径（≤ titlePathMaxLevel 级） */
    private List<String> titlePath = new ArrayList<>();

    /** 正文聚合缓冲（段落/标题边界/句子等跨元素聚合切片器使用；每次运行独立） */
    private List<ViewElement> bodyBuffer = new ArrayList<>();

    /** 当前前导文本段落（表+引导段落算法用；每遇非空 BODY 元素更新） */
    private String leadParagraph;
}
