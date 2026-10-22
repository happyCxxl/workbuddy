package com.workbuddy.model.service.support.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 注册表刷新器单测：无事务立即重载；有事务时挂到提交后执行。
 */
class ModelRegistryRefresherTest {

    private final ModelRegistryLoader loader = mock(ModelRegistryLoader.class);

    private final ModelRegistryRefresher refresher = new ModelRegistryRefresher(loader);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 无事务：立即 reload（{@code shouldReloadImmediatelyWithoutTransaction}）。 */
    @Test
    void shouldReloadImmediatelyWithoutTransaction() {
        refresher.refreshAfterCommit();

        verify(loader).reload();
    }

    /** 有事务：提交后才 reload，注册时点不调用（{@code shouldReloadOnlyAfterCommit}）。 */
    @Test
    void shouldReloadOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        refresher.refreshAfterCommit();

        verify(loader, never()).reload();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(loader).reload();
    }
}
