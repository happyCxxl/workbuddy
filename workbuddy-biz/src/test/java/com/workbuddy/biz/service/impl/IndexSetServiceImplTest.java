package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.IndexBuildService;
import com.workbuddy.biz.service.IndexComboQueryService;
import com.workbuddy.biz.service.IndexPublishService;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.IndexVersionService;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.worker.indexing.BuildOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制面门面单测：只验证每个用例委托到对应子服务，业务行为由子服务测试覆盖。
 */
class IndexSetServiceImplTest {

    private IndexBuildService buildService;
    private IndexPublishService publishService;
    private IndexVersionService versionService;
    private IndexComboQueryService comboQueryService;
    private IndexSetServiceImpl service;

    @BeforeEach
    void setUp() {
        buildService = mock(IndexBuildService.class);
        publishService = mock(IndexPublishService.class);
        versionService = mock(IndexVersionService.class);
        comboQueryService = mock(IndexComboQueryService.class);
        service = new IndexSetServiceImpl(buildService, publishService, versionService, comboQueryService);
    }

    /**
     * 产物就绪回调委托构建服务。
     *
     * <p>守护 {@link IndexSetService#onFileProductsReady(Long)}：门面自行处理即失败。
     */
    @Test
    void onFileProductsReadyShouldDelegateToBuildService() {
        service.onFileProductsReady(10L);

        verify(buildService).onFileProductsReady(10L);
    }

    /**
     * 候选构建委托构建服务并原样返回结果。
     *
     * <p>守护 {@link IndexSetService#buildCandidate(BuildOrder)}：替换返回值即失败。
     */
    @Test
    void buildCandidateShouldDelegateToBuildService() {
        BuildOrder order = new BuildOrder();
        IndexBuildTriggerVO result = new IndexBuildTriggerVO(1L, "v1", 2L);
        when(buildService.buildCandidate(order)).thenReturn(result);

        assertSame(result, service.buildCandidate(order));
        verify(buildService).buildCandidate(order);
    }

    /**
     * 发布委托发布服务。
     *
     * <p>守护 {@link IndexSetService#publish(Long)}：门面绕过发布服务即失败。
     */
    @Test
    void publishShouldDelegateToPublishService() {
        service.publish(20L);

        verify(publishService).publish(20L);
    }

    /**
     * 当前在线版本委托发布服务并原样返回。
     *
     * <p>守护 {@link IndexPublishService#currentPublished(Long)}：替换返回值即失败。
     */
    @Test
    void currentPublishedShouldDelegateToPublishService() {
        WbIndexVersion version = new WbIndexVersion();
        when(publishService.currentPublished(1L)).thenReturn(version);

        assertSame(version, service.currentPublished(1L));
        verify(publishService).currentPublished(1L);
    }

    /**
     * 回退委托版本服务。
     *
     * <p>守护 {@link IndexSetService#rollback(Long)}：门面绕过版本服务即失败。
     */
    @Test
    void rollbackShouldDelegateToVersionService() {
        service.rollback(15L);

        verify(versionService).rollback(15L);
    }

    /**
     * 回收委托版本服务。
     *
     * <p>守护 {@link IndexSetService#recycle(Long)}：门面绕过版本服务即失败。
     */
    @Test
    void recycleShouldDelegateToVersionService() {
        service.recycle(30L);

        verify(versionService).recycle(30L);
    }

    /**
     * 版本列表委托发布服务并原样返回。
     *
     * <p>守护 {@link IndexSetService#listVersions(Long)}：替换列表即失败。
     */
    @Test
    void listVersionsShouldDelegateToPublishService() {
        List<IndexVersionVO> versions = List.of(new IndexVersionVO());
        when(publishService.listVersions(1L)).thenReturn(versions);

        assertEquals(versions, service.listVersions(1L));
        verify(publishService).listVersions(1L);
    }

    /**
     * 版本详情委托发布服务并原样返回。
     *
     * <p>守护 {@link IndexSetService#versionDetail(Long, Long)}：替换详情即失败。
     */
    @Test
    void versionDetailShouldDelegateToPublishService() {
        IndexVersionVO detail = new IndexVersionVO();
        when(publishService.versionDetail(1L, 20L)).thenReturn(detail);

        assertSame(detail, service.versionDetail(1L, 20L));
        verify(publishService).versionDetail(1L, 20L);
    }

    /**
     * 组合列表委托组合查询服务并原样返回。
     *
     * <p>守护 {@link IndexSetService#listCombos(Long, List)}：替换列表即失败。
     */
    @Test
    void listCombosShouldDelegateToComboQueryService() {
        List<IndexComboVO> combos = List.of(new IndexComboVO());
        when(comboQueryService.listCombos(1L, null)).thenReturn(combos);

        assertEquals(combos, service.listCombos(1L, null));
        verify(comboQueryService).listCombos(1L, null);
    }

    /**
     * 校验委托构建服务并原样返回。
     *
     * <p>守护 {@link IndexSetService#validate(Long)}：替换校验结果即失败。
     */
    @Test
    void validateShouldDelegateToBuildService() {
        IndexValidateVO result = new IndexValidateVO();
        when(buildService.validate(20L)).thenReturn(result);

        assertSame(result, service.validate(20L));
        verify(buildService).validate(20L);
    }
}
