package com.workbuddy.filecenter.config;

import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.filecenter.provider.StorageSourceDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 内置数据源定义单测。
 *
 * <p>守护 {@code seedDefs}/{@code defOfType} 的口径：两条内置定义（MinIO、本地磁盘）
 * 参数与配置一一对应；缺省配置也能生成完整参数（回落值不缺项）。
 */
class FileCenterDefaultsTest {

    /**
     * 默认配置下两条内置定义齐备，参数完整且类型正确。
     *
     * <p>守护 {@code seedDefs}：漏一条定义或参数缺项即失败。
     */
    @Test
    void seedDefsShouldContainBothTypesWithFullParams() {
        FileCenterConfig config = new FileCenterConfig();

        List<StorageSourceDef> defs = FileCenterDefaults.seedDefs(config);

        assertEquals(2, defs.size());
        assertEquals(StorageType.MINIO, defs.get(0).type());
        assertEquals(StorageType.LOCAL, defs.get(1).type());
        assertEquals(FileCenterDefaults.MINIO_NAME, defs.get(0).name());
        assertEquals(FileCenterDefaults.LOCAL_NAME, defs.get(1).name());
        assertNotNull(defs.get(0).id());
        assertNotNull(defs.get(1).id());
    }

    /**
     * 配置指定的端点与凭据进入 MinIO 定义参数。
     *
     * <p>守护 {@code minioDef} 的参数映射：键值错位即失败（后端连不上）。
     */
    @Test
    void seedDefsShouldCarryMinioParamsFromConfig() {
        FileCenterConfig config = new FileCenterConfig();
        config.getMinio().setEndpoint("http://10.0.0.2:9000");
        config.getMinio().setAccessKey("ak");
        config.getMinio().setSecretKey("sk");
        config.getMinio().setFileBucket("files");
        config.getMinio().setArtifactBucket("artifacts");

        StorageSourceDef minio = FileCenterDefaults.seedDefs(config).get(0);

        assertEquals("http://10.0.0.2:9000", minio.param(StorageSourceDef.KEY_ENDPOINT));
        assertEquals("ak", minio.param(StorageSourceDef.KEY_ACCESS_KEY));
        assertEquals("sk", minio.param(StorageSourceDef.KEY_SECRET_KEY));
        assertEquals("files", minio.fileBucket());
        assertEquals("artifacts", minio.artifactBucket());
    }

    /**
     * 本地磁盘定义携带存储根与两个目录名。
     *
     * <p>守护 {@code localDef} 的参数映射：目录错位即失败（对象写到错误位置）。
     */
    @Test
    void seedDefsShouldCarryLocalDirsFromConfig() {
        FileCenterConfig config = new FileCenterConfig();
        config.getLocal().setRootDir("./data/storage");
        config.getLocal().setFileDir("files");
        config.getLocal().setArtifactDir("artifacts");

        StorageSourceDef local = FileCenterDefaults.seedDefs(config).get(1);

        assertEquals("./data/storage", local.param(StorageSourceDef.KEY_ROOT_DIR));
        assertEquals("files", local.fileBucket());
        assertEquals("artifacts", local.artifactBucket());
    }

    /**
     * 按类型取内置定义：MinIO 取 MinIO，其余取本地。
     *
     * <p>守护 {@code defOfType} 的分派：类型写反即失败。
     */
    @Test
    void defOfTypeShouldDispatchByType() {
        FileCenterConfig config = new FileCenterConfig();

        assertEquals(StorageType.MINIO,
                FileCenterDefaults.defOfType(config, StorageType.MINIO).type());
        assertEquals(StorageType.LOCAL,
                FileCenterDefaults.defOfType(config, StorageType.LOCAL).type());
    }
}
