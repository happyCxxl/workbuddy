package com.workbuddy.model.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 注册表刷新器：模型写入提交后重读启用行，保证「库里已提交」与「内存已刷新」一致。
 *
 * <p>刷新失败只告警，不影响已提交的业务写入；下次启动或下次写入会重新装载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRegistryRefresher {

    private final ModelRegistryLoader loader;

    /**
     * 在事务提交后刷新注册表；当前没有事务时立即刷新。
     */
    public void refreshAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 事务提交后重载注册表。 */
                @Override
                public void afterCommit() {
                    safeReload();
                }
            });
            return;
        }
        safeReload();
    }

    /** 重读注册表：失败只告警，保留旧快照 */
    private void safeReload() {
        try {
            loader.reload();
        } catch (RuntimeException e) {
            log.warn("===> ModelRegistryRefresher 注册表刷新失败，保留旧快照, error={}", e.getMessage());
        }
    }
}
