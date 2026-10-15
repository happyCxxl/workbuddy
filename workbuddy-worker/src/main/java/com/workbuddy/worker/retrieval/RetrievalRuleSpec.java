package com.workbuddy.worker.retrieval;

import lombok.Data;

/**
 * 检索规则快照（检索策略模型）：一条规则 = 一张完整配置快照
 * （kb_pipeline_strategy_version.config_snapshot，type=RETRIEVAL，行不可变）。
 * 全目录建全、预留项锁定：未启用能力取值 NONE/0，注册校验（RetrievalRuleResolver）
 * 与执行引擎双保险拒绝；解锁只改能力开关，结构零迁移。
 *
 * @author cxxl
 */
@Data
public class RetrievalRuleSpec {

    public static final String CHANNEL_FULLTEXT = "FULLTEXT";
    public static final String CHANNEL_VECTOR = "VECTOR";
    public static final String CHANNEL_HYBRID = "HYBRID";

    public static final String FUSION_RRF = "RRF";
    public static final String FUSION_WEIGHTED = "WEIGHTED";

    public static final String MODE_NONE = "NONE";
    public static final String MODE_REWRITE = "REWRITE";
    public static final String MODE_EXPAND = "EXPAND";
    public static final String MODE_RERANK = "RERANK";
    public static final String MODE_PARENT_EXPAND = "PARENT_EXPAND";
    public static final String MODE_NEIGHBOR_EXPAND = "NEIGHBOR_EXPAND";

    /** 召回通道：FULLTEXT / VECTOR / HYBRID */
    private String channel = CHANNEL_HYBRID;

    /** 融合配置（HYBRID 下生效） */
    private Fusion fusion = new Fusion();

    /** 查询预处理（NONE / 预留 REWRITE/EXPAND） */
    private StageAction preprocess = new StageAction();

    /** 重排（NONE / 预留 RERANK） */
    private StageAction rerank = new StageAction();

    /** 后处理（NONE / PARENT_EXPAND / 预留 NEIGHBOR_EXPAND） */
    private StageAction postprocess = new StageAction();

    /** 最终返回条数（请求可覆盖） */
    private int topK = 10;

    /** 向量相似度阈值（0 = 不启用；预留截断能力） */
    private double scoreThreshold = 0;

    /** 引擎基线规则（回退链第三级，不落库）：混合 RRF + 父片展开 */
    public static RetrievalRuleSpec baseline() {
        RetrievalRuleSpec spec = new RetrievalRuleSpec();
        spec.getPostprocess().setMode(MODE_PARENT_EXPAND);
        return spec;
    }

    /**
     * 双通道融合配置。
     */
    @Data
    public static class Fusion {

        /** RRF / 预留 WEIGHTED */
        private String mode = FUSION_RRF;

        /** RRF 常数 k */
        private int rrfK = 60;

        /** 每通道召回条数上限 */
        private int perChannelLimit = 50;
    }

    /**
     * 环节动作（查询预处理/重排/后处理）：mode 取值白名单按环节由校验器收口。
     */
    @Data
    public static class StageAction {

        /** NONE / 各环节预留取值 */
        private String mode = MODE_NONE;
    }
}
