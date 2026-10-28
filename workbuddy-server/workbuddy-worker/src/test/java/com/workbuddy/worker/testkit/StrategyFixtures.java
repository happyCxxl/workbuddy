package com.workbuddy.worker.testkit;

import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;

/**
 * 测试作用域的环节策略夹具：按模板参数（与 init.sql 种子行同参）补全的基线策略。
 * 主代码不再保留任何内置默认策略，用例的基线由这里以「模板名/版本 + 空配置」解析得到。
 */
public final class StrategyFixtures {

    /** 切片策略模板（chunk-hybrid / v1；空配置即全默认路由与流程层参数） */
    private static final String CHUNK_TEMPLATE = "{\"name\":\"chunk-hybrid\",\"version\":\"v1\"}";

    /** 预处理策略模板（preproc-default / v1；空配置即七规则全默认参数） */
    private static final String PREPROCESS_TEMPLATE = "{\"name\":\"preproc-default\",\"version\":\"v1\"}";

    private StrategyFixtures() {
    }

    /** 切片策略模板（默认 ChunkProperties） */
    public static ChunkStrategy chunkDefaults() {
        return chunkDefaults(PropertiesFixtures.chunk());
    }

    /** 切片策略模板（模板参数按传入 properties 补全） */
    public static ChunkStrategy chunkDefaults(ChunkProperties properties) {
        return new ChunkStrategyParser(properties).parse(CHUNK_TEMPLATE);
    }

    /** 预处理策略模板（七规则与参数按默认 PreprocessProperties 补全） */
    public static PreprocessStrategy preprocessDefaults() {
        return new PreprocessStrategyParser(PropertiesFixtures.preprocess()).parse(PREPROCESS_TEMPLATE);
    }
}
