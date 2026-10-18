package com.workbuddy.filecenter.startup;

import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.filecenter.config.FileCenterConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储类型校验单测：合法码值通过并推进链；非法码值即拒且不推进。
 */
class StorageTypeCheckTest {

    /** 链的替身：记录是否被推进 */
    private final AtomicBoolean proceeded = new AtomicBoolean();

    private final StartupCheckChain next = () -> proceeded.set(true);

    @Test
    void shouldPassWithKnownStorageType() {
        new StorageTypeCheck(config("minio")).check(next);

        assertTrue(proceeded.get(), "通过路径必须推进链");
    }

    @Test
    void shouldPassWithLocalStorageType() {
        assertDoesNotThrow(() -> new StorageTypeCheck(config("local")).check(next));
    }

    @Test
    void shouldRejectUnknownStorageType() {
        FileCenterConfig config = config("s3");

        KnowledgeException error = assertThrows(KnowledgeException.class,
                () -> new StorageTypeCheck(config).check(next));

        assertTrue(error.getMessage().contains("s3"), () -> "message=" + error.getMessage());
        assertFalse(proceeded.get(), "失败即止，不推进链");
    }

    private FileCenterConfig config(String storageType) {
        FileCenterConfig config = new FileCenterConfig();
        config.setStorageType(storageType);
        return config;
    }
}
