package com.workbuddy.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.database.request.CreateDatabaseReq;
import io.milvus.v2.service.database.response.ListDatabasesResp;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Milvus 客户端工厂单测：缺库时以 default 库探测并建库、库已存在时只连不建、default 与空库名不探测。
 */
class MilvusClientFactoryTest {

    private MilvusClientFactory factory(String database) {
        MilvusProperties properties = new MilvusProperties();
        properties.setDatabase(database);
        return new MilvusClientFactory(properties);
    }

    private ListDatabasesResp databases(String... names) {
        ListDatabasesResp resp = mock(ListDatabasesResp.class);
        when(resp.getDatabaseNames()).thenReturn(List.of(names));
        return resp;
    }

    /** 每次构造客户端时记下连接参数里的库名（按构造顺序） */
    private String dbName(ConnectConfig config) {
        return config.getDbName();
    }

    @Test
    void shouldCreateOnlyWhenTargetMissing() {
        assertTrue(MilvusClientFactory.shouldCreate(List.of("default"), "knowledge"));
        assertFalse(MilvusClientFactory.shouldCreate(List.of("default", "knowledge"), "knowledge"));
        assertFalse(MilvusClientFactory.shouldCreate(List.of("default"), "default"));
        assertFalse(MilvusClientFactory.shouldCreate(List.of("default"), ""));
        assertFalse(MilvusClientFactory.shouldCreate(List.of("default"), null));
    }

    @Test
    void missingDatabaseShouldBeCreatedThroughDefaultConnection() {
        List<String> connected = new ArrayList<>();
        ListDatabasesResp visible = databases("default");
        try (MockedConstruction<MilvusClientV2> construction = mockConstruction(MilvusClientV2.class,
                (mock, context) -> {
                    connected.add(dbName((ConnectConfig) context.arguments().get(0)));
                    when(mock.listDatabases()).thenReturn(visible);
                })) {
            MilvusClientV2 client = factory("knowledge").milvusClientV2();

            assertNotNull(client);
            // 两次构造：先 default 探测建库，再以目标库正式连接
            assertEquals(List.of("default", "knowledge"), connected);
            verify(construction.constructed().get(0)).createDatabase(any(CreateDatabaseReq.class));
        }
    }

    @Test
    void existingDatabaseShouldNotBeCreated() {
        List<String> connected = new ArrayList<>();
        ListDatabasesResp visible = databases("default", "knowledge");
        try (MockedConstruction<MilvusClientV2> construction = mockConstruction(MilvusClientV2.class,
                (mock, context) -> {
                    connected.add(dbName((ConnectConfig) context.arguments().get(0)));
                    when(mock.listDatabases()).thenReturn(visible);
                })) {
            factory("knowledge").milvusClientV2();

            assertEquals(List.of("default", "knowledge"), connected);
            verify(construction.constructed().get(0), never()).createDatabase(any(CreateDatabaseReq.class));
        }
    }

    @Test
    void defaultDatabaseShouldSkipProbe() {
        List<String> connected = new ArrayList<>();
        try (MockedConstruction<MilvusClientV2> construction = mockConstruction(MilvusClientV2.class,
                (mock, context) -> connected.add(dbName((ConnectConfig) context.arguments().get(0))))) {
            factory("default").milvusClientV2();

            assertEquals(List.of("default"), connected);
        }
    }

    @Test
    void blankDatabaseShouldSkipProbe() {
        List<String> connected = new ArrayList<>();
        try (MockedConstruction<MilvusClientV2> construction = mockConstruction(MilvusClientV2.class,
                (mock, context) -> connected.add(dbName((ConnectConfig) context.arguments().get(0))))) {
            factory("  ").milvusClientV2();

            assertEquals(1, connected.size());
        }
    }

    @Test
    void probeFailureShouldStillConnectWithConfiguredDatabase() {
        List<String> connected = new ArrayList<>();
        try (MockedConstruction<MilvusClientV2> construction = mockConstruction(MilvusClientV2.class,
                (mock, context) -> {
                    connected.add(dbName((ConnectConfig) context.arguments().get(0)));
                    when(mock.listDatabases()).thenThrow(new IllegalStateException("探测不可用"));
                })) {
            MilvusClientV2 client = factory("knowledge").milvusClientV2();

            assertNotNull(client);
            assertEquals(List.of("default", "knowledge"), connected);
        }
    }
}
