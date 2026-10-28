package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.biz.service.support.index.IndexLineageResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引血缘解析单测。
 *
 * <p>守护 {@code resolvePreprocessStrategy} 的三个口径：上游产物缺失或快照为空返回 null（血统不完整），
 * 快照结构缺失（无策略名）返回 null，合法快照拼出 name-version；解析失败只告警不抛出。
 */
@ExtendWith(MockitoExtension.class)
class IndexLineageResolverTest {

    @Mock
    private WbPipelineProductDbService productDbService;

    private IndexLineageResolver resolver;

    /**
     * 每个用例重建解析器。
     */
    @BeforeEach
    void setUp() {
        resolver = new IndexLineageResolver(productDbService);
    }

    /**
     * 上游产物 ID 为空：直接返回 null，不查库。
     *
     * <p>守护 {@code resolvePreprocessStrategy} 的空入参兜底：空 ID 查库会拿到随机行。
     */
    @Test
    void resolveShouldReturnNullForNullId() {
        assertNull(resolver.resolvePreprocessStrategy(null));

        verify(productDbService, never()).getById(any());
    }

    /**
     * 产物行缺失或快照为空：返回 null。
     *
     * <p>守护 {@code resolvePreprocessStrategy} 的产物/快照缺失分支：空快照去解析会抛出。
     */
    @Test
    void resolveShouldReturnNullWhenProductOrSnapshotMissing() {
        when(productDbService.getById(100L)).thenReturn(null);
        assertNull(resolver.resolvePreprocessStrategy(100L));

        WbPipelineProduct blankSnapshot = new WbPipelineProduct();
        blankSnapshot.setId(101L);
        blankSnapshot.setCapabilitySnapshot(" ");
        when(productDbService.getById(101L)).thenReturn(blankSnapshot);
        assertNull(resolver.resolvePreprocessStrategy(101L));
    }

    /**
     * 合法快照拼出 name-version。
     *
     * <p>守护 {@code resolvePreprocessStrategy} 的成功分支：字段拼错会让组合判等永远不成立。
     */
    @Test
    void resolveShouldReturnFullVersion() {
        when(productDbService.getById(100L)).thenReturn(
                product("{\"type\":\"PREPROCESS\",\"name\":\"preproc-default\",\"version\":\"v1\"}"));

        assertEquals("preproc-default-v1", resolver.resolvePreprocessStrategy(100L));
    }

    /**
     * 快照缺策略名：返回 null（结构缺失）。
     *
     * <p>守护 {@code resolvePreprocessStrategy} 的结构校验：拼出「-v1」会被当成有效策略。
     */
    @Test
    void resolveShouldReturnNullWhenNameBlank() {
        when(productDbService.getById(100L)).thenReturn(
                product("{\"type\":\"PREPROCESS\",\"version\":\"v1\"}"));
        assertNull(resolver.resolvePreprocessStrategy(100L));

        when(productDbService.getById(101L)).thenReturn(
                product("{\"type\":\"PREPROCESS\",\"name\":\"  \",\"version\":\"v1\"}"));
        assertNull(resolver.resolvePreprocessStrategy(101L));
    }

    /**
     * 快照不是合法 JSON：返回 null 并告警，不中断调用方。
     *
     * <p>守护 {@code resolvePreprocessStrategy} 的解析兜底：抛异常会打断组合枚举。
     */
    @Test
    void resolveShouldReturnNullOnMalformedSnapshot() {
        when(productDbService.getById(100L)).thenReturn(product("not-json"));

        assertNull(resolver.resolvePreprocessStrategy(100L));
    }

    /** 产物行夹具：只填 ID 与能力快照 */
    private WbPipelineProduct product(String capabilitySnapshot) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setId(100L);
        product.setCapabilitySnapshot(capabilitySnapshot);
        return product;
    }
}
