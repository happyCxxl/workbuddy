package com.workbuddy.vector;

import com.workbuddy.common.utils.NullUtil;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Milvus 客户端工厂：统一生命周期（创建/关闭），业务层只取 Bean 使用。
 * 关闭开关（knowledge.vector.milvus.enable=false）时不注册，索引/检索环节跳过。
 *
 * <p>连接前先保证目标库存在：Milvus 只自带 {@code default} 库，而连接参数里的库名必须是已存在的库，
 * 库名对不上时客户端建不出来、整个应用起不来。缺库时先用 {@code default} 库连一次并建库，口径与集合懒建一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "knowledge.vector.milvus.enable", havingValue = "true", matchIfMissing = true)
public class MilvusClientFactory {

    /** 内置默认库：不需要探测，也无法作为"缺库"处理 */
    private static final String DEFAULT_DATABASE = "default";

    private final MilvusProperties properties;

    private volatile MilvusClientV2 client;

    /**
     * 客户端 Bean（单例）：先确保目标库存在，再按配置连接。
     */
    /**
     * 客户端 Bean（单例）：先确保目标库存在，再按配置连接。
     *
     * @return Milvus 客户端
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        ensureDatabaseExists();
        ConnectConfig config = connectConfig(properties.getDatabase());
        client = new MilvusClientV2(config);
        log.info("===> MilvusClientFactory Milvus 客户端已创建, uri={}, database={}",
                config.getUri(), config.getDbName());
        return client;
    }

    /**
     * 应用关闭时释放连接。
     */
    @PreDestroy
    public void close() {
        closeQuietly(client);
    }

    /** 连接配置：uri + 库名 + 超时 */
    private ConnectConfig connectConfig(String database) {
        //noinspection HttpUrlsUsage
        return ConnectConfig.builder()
                .uri("http://" + properties.getHost() + ":" + properties.getPort())
                .dbName(database)
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .build();
    }

    /**
     * 目标库不存在则创建：以 {@code default} 库连一个临时客户端探测并建库，随后关闭临时客户端。
     * 探测或建库失败不改变原有行为，仍按配置连接（连接错误如实抛出，不在这里吞掉）。
     */
    private void ensureDatabaseExists() {
        String database = properties.getDatabase();
        if (isDefaultDatabase(database)) {
            return;
        }
        MilvusClientV2 bootstrap = null;
        try {
            bootstrap = new MilvusClientV2(connectConfig(DEFAULT_DATABASE));
            if (!shouldCreate(bootstrap.listDatabases().getDatabaseNames(), database)) {
                return;
            }
            bootstrap.createDatabase(CreateDatabaseReq.builder().databaseName(database).build());
            log.info("===> MilvusClientFactory Milvus 库不存在，已创建, database={}", database);
        } catch (Exception e) {
            log.warn("Milvus 库探测/创建失败，按配置连接, database={}", database, e);
        } finally {
            closeQuietly(bootstrap);
        }
    }

    /** 关客户端：为 null 直接返回，关闭异常只告警（生命周期收尾不抛） */
    private void closeQuietly(MilvusClientV2 clientToClose) {
        if (NullUtil.isNull(clientToClose)) {
            return;
        }
        try {
            clientToClose.close();
        } catch (Exception e) {
            log.warn("Milvus 客户端关闭异常", e);
        }
    }

    /** 是否是无须探测的库：空值按 SDK 的 default 处理 */
    private static boolean isDefaultDatabase(String database) {
        return NullUtil.isNull(database) || database.isBlank() || DEFAULT_DATABASE.equalsIgnoreCase(database);
    }

    /**
     * 可见库清单里没有目标库就需要创建（清单取不到时按需要创建处理，建重了也只是幂等失败）。
     */
    static boolean shouldCreate(List<String> existingDatabases, String database) {
        if (isDefaultDatabase(database)) {
            return false;
        }
        return NullUtil.isNull(existingDatabases) || !existingDatabases.contains(database);
    }
}
