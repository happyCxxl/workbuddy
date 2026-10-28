package com.workbuddy.infra.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接配置（knowledge.vector.milvus 前缀；同名 kebab-case 键在
 * config/knowledge/knowledge-milvus.yml）。字段不带代码侧默认值，配置面是唯一基线。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.vector.milvus")
public class MilvusProperties {

    /** 是否启用向量库（false = 不注册客户端 Bean，索引/检索环节跳过） */
    private boolean enable;

    /** 服务地址 */
    private String host;

    /** 端口 */
    private int port;

    /** 数据库名 */
    private String database;

    /** 连接超时（毫秒） */
    private long connectTimeoutMs;
}
