package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.access.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.access.KnowledgeBaseBindingSupport;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库策略绑定服务单测：单类型查询/绑定/解绑、批量设置与入参校验。
 */
class KnowledgeBaseBindingServiceImplTest extends KnowledgeBaseTestFixture {

    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    private WbStrategyBindingDbService strategyBindingDbService;

    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    private KnowledgeBaseBindingServiceImpl service;

    /**
     * 每个用例重建数据访问替身与服务，并默认以普通用户登录。
     */
    @BeforeEach
    void setUp() {
        knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
        strategyBindingDbService = mock(WbStrategyBindingDbService.class);
        strategyVersionDbService = mock(WbPipelineStrategyVersionDbService.class);
        service = new KnowledgeBaseBindingServiceImpl(strategyBindingDbService,
                strategyVersionDbService, new KnowledgeBaseAccessSupport(knowledgeBaseDbService),
                new KnowledgeBaseBindingSupport(strategyBindingDbService, strategyVersionDbService));
        login(ME, UserRole.USER);
    }

    /**
     * 查绑定：类型、版本 ID/名称/版本号全量返回。
     *
     * <p>守护 {@code strategyBinding}：漏搬任一字段即失败。
     */
    @Test
    void strategyBindingShouldReturnBoundVersion() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());

        StrategyBindingVO vo = service.strategyBinding(2L, "CHUNK");

        assertEquals("CHUNK", vo.getStrategyType());
        assertEquals(66L, vo.getStrategyVersionId());
        assertEquals("chunk-hybrid", vo.getStrategyName());
        assertEquals("v1", vo.getStrategyVersion());
    }

    /**
     * 绑定不存在版本 / 类型与版本不匹配 / 未知类型三种请求一律 40001。
     *
     * <p>守护 {@code bindStrategy} 的三道入参校验：放行任何一个都会把错版本绑上去。
     */
    @Test
    void bindStrategyShouldRejectUnknownTypeMissingVersionAndTypeMismatch() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        StrategyBindingUpdateDTO unknownType = new StrategyBindingUpdateDTO();
        unknownType.setStrategyType("INDEX");
        unknownType.setStrategyVersionId(66L);
        WorkbuddyException typeError = assertThrows(WorkbuddyException.class,
                () -> service.bindStrategy(2L, unknownType));
        assertEquals(ErrorCode.PARAM_INVALID, typeError.getErrorCode());
        assertTrue(typeError.getMessage().contains("未知策略类型"), typeError.getMessage());

        StrategyBindingUpdateDTO missingVersion = new StrategyBindingUpdateDTO();
        missingVersion.setStrategyType("CHUNK");
        missingVersion.setStrategyVersionId(999L);
        when(strategyVersionDbService.getById(999L)).thenReturn(null);
        WorkbuddyException missing = assertThrows(WorkbuddyException.class,
                () -> service.bindStrategy(2L, missingVersion));
        assertEquals(ErrorCode.PARAM_INVALID, missing.getErrorCode());
        assertTrue(missing.getMessage().contains("策略版本不存在"), missing.getMessage());

        WbPipelineStrategyVersion embedVersion = new WbPipelineStrategyVersion();
        embedVersion.setId(88L);
        embedVersion.setType("EMBED");
        embedVersion.setStatus("ACTIVE");
        StrategyBindingUpdateDTO mismatch = new StrategyBindingUpdateDTO();
        mismatch.setStrategyType("CHUNK");
        mismatch.setStrategyVersionId(88L);
        when(strategyVersionDbService.getById(88L)).thenReturn(embedVersion);
        WorkbuddyException mismatchError = assertThrows(WorkbuddyException.class,
                () -> service.bindStrategy(2L, mismatch));
        assertEquals(ErrorCode.PARAM_INVALID, mismatchError.getErrorCode());
        assertTrue(mismatchError.getMessage().contains("策略类型与所选版本不匹配"), mismatchError.getMessage());

        verify(strategyBindingDbService, never()).save(any());
        verify(strategyBindingDbService, never()).updateById(any());
    }

    /**
     * 首次绑定：新行把知识库、类型、版本 ID 全部落库。
     *
     * <p>守护 {@code bindStrategy} 的新建分支：漏写任一列都会让绑定在查询侧失效。
     */
    @Test
    void bindStrategyShouldCreateRowWithAllFields() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(null);
        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(service.bindStrategy(2L, dto));

        ArgumentCaptor<WbStrategyBinding> captor = ArgumentCaptor.forClass(WbStrategyBinding.class);
        verify(strategyBindingDbService).save(captor.capture());
        assertEquals(2L, captor.getValue().getKnowledgeBaseId());
        assertEquals("CHUNK", captor.getValue().getStrategyType());
        assertEquals(66L, captor.getValue().getStrategyVersionId());
    }

    /**
     * 查绑定：未知类型按 40001 拒绝。
     *
     * <p>守护 {@code strategyBinding}：白名单失效即失败。
     */
    @Test
    void strategyBindingWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        WorkbuddyException e = assertThrows(WorkbuddyException.class,
                () -> service.strategyBinding(2L, "INDEX"));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
    }

    /**
     * 绑定：复用含已逻辑删除的原行并恢复删除标记。
     *
     * <p>守护 {@code bindStrategy}：新建重复行即失败。
     */
    @Test
    void bindStrategyShouldReuseExistingRow() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyVersionDbService.getById(66L)).thenReturn(chunkVersion());
        WbStrategyBinding existing = binding(55L);
        existing.setDelFlag(DelFlag.DELETED.getCode());
        when(strategyBindingDbService.getAnyByKbAndType(2L, "CHUNK")).thenReturn(existing);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        assertTrue(service.bindStrategy(2L, dto));

        assertEquals(DelFlag.NORMAL.getCode(), existing.getDelFlag());
        assertEquals(66L, existing.getStrategyVersionId());
        verify(strategyBindingDbService).updateById(existing);
    }

    /**
     * 绑定：版本为空即解绑（逻辑删除绑定行）。
     *
     * <p>守护 {@code bindStrategy}：解绑不做或审计丢失即失败。
     */
    @Test
    void bindStrategyWithNullVersionShouldUnbind() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        when(strategyBindingDbService.getByKbAndType(2L, "CHUNK")).thenReturn(binding(66L));

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(null);

        assertTrue(service.bindStrategy(2L, dto));

        ArgumentCaptor<WbStrategyBinding> captor = ArgumentCaptor.forClass(WbStrategyBinding.class);
        verify(strategyBindingDbService).updateById(captor.capture());
        assertEquals(DelFlag.DELETED.getCode(), captor.getValue().getDelFlag());
    }

    /**
     * 绑定：停用策略拒绝绑定。
     *
     * <p>守护 {@code bindStrategy}：停用版本被绑上去即失败。
     */
    @Test
    void bindStrategyWithInactiveVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        WbPipelineStrategyVersion inactive = chunkVersion();
        inactive.setStatus("INACTIVE");
        when(strategyVersionDbService.getById(66L)).thenReturn(inactive);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.bindStrategy(2L, dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：三件套齐全时逐项绑定成功。
     *
     * <p>守护 {@code bindStrategies}：漏绑任一环节即失败。
     */
    @Test
    void bindStrategiesShouldAcceptFullTriple() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));
        stubActiveVersions(strategyVersionDbService, "PREPROCESS", 11L);
        stubActiveVersions(strategyVersionDbService, "CHUNK", 22L);
        stubActiveVersions(strategyVersionDbService, "EMBED", 33L);
        when(strategyBindingDbService.getAnyByKbAndType(eq(2L), anyString())).thenReturn(null);

        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        assertTrue(service.bindStrategies(2L, request));

        verify(strategyBindingDbService, times(3)).save(any(WbStrategyBinding.class));
    }

    /**
     * 批量绑定：缺环节按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：缺项被放行即失败。
     */
    @Test
    void bindStrategiesWithPartialTripleShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 只给预处理与切片的组合，缺向量化
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L));

        WorkbuddyException e =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("需一次设置全部可绑定策略"), e.getMessage());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：任一项版本为空按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：空版本被放行即失败。
     */
    @Test
    void bindStrategiesWithNullVersionShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 三件套类型齐全，但向量化没给版本 —— 会造成"部分走绑定、部分走最新启用"的隐性不一致
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", null));

        WorkbuddyException e =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("策略版本不能为空"), e.getMessage());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：绑定开关关闭时拒绝。
     *
     * <p>守护 {@code bindStrategies}：测评库被绑定策略即失败。
     */
    @Test
    void bindStrategiesWhenBindingDisabledShouldReject() {
        WbKnowledgeBase disabled = kb(2L, 1);
        disabled.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(disabled);

        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("EMBED", 33L));

        WorkbuddyException e =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("已关闭策略绑定"), e.getMessage());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：未知类型按 40001 拒绝。
     *
     * <p>守护 {@code bindStrategies}：白名单失效即失败。
     */
    @Test
    void bindStrategiesWithUnknownTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        // 检索类策略不绑知识库（走索引版本的默认检索规则）
        StrategyBindingsUpdateDTO request = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("RETRIEVAL", 44L));

        WorkbuddyException e =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, request));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("未知策略类型"), e.getMessage());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 单类型绑定：开关关闭时拒绝。
     *
     * <p>守护 {@code bindStrategy}：测评库被绑定策略即失败。
     */
    @Test
    void bindStrategyWhenBindingDisabledShouldReject() {
        WbKnowledgeBase closed = kb(2L, 1);
        closed.setStrategyBindingEnabled(0);
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(closed);

        StrategyBindingUpdateDTO dto = new StrategyBindingUpdateDTO();
        dto.setStrategyType("CHUNK");
        dto.setStrategyVersionId(66L);

        WorkbuddyException e = assertThrows(WorkbuddyException.class, () -> service.bindStrategy(2L, dto));
        assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        assertTrue(e.getMessage().contains("已关闭策略绑定"), e.getMessage());
        verify(strategyBindingDbService, never()).save(any());
    }

    /**
     * 批量绑定：空列表与重复类型一律 40001。
     *
     * <p>守护 {@code bindStrategies} 的入参校验：空列表静默成功会让调用方以为绑定已生效。
     */
    @Test
    void bindStrategiesWithEmptyOrDuplicateTypeShouldReject() {
        when(knowledgeBaseDbService.getActiveById(2L)).thenReturn(kb(2L, 1));

        StrategyBindingsUpdateDTO empty = new StrategyBindingsUpdateDTO();
        empty.setBindings(List.of());
        WorkbuddyException emptyError =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, empty));
        assertEquals(ErrorCode.PARAM_INVALID, emptyError.getErrorCode());
        assertTrue(emptyError.getMessage().contains("绑定列表不能为空"), emptyError.getMessage());

        StrategyBindingsUpdateDTO duplicated = bindRequest(
                bindItem("PREPROCESS", 11L), bindItem("CHUNK", 22L), bindItem("CHUNK", 33L));
        WorkbuddyException dupError =
                assertThrows(WorkbuddyException.class, () -> service.bindStrategies(2L, duplicated));
        assertEquals(ErrorCode.PARAM_INVALID, dupError.getErrorCode());
        assertTrue(dupError.getMessage().contains("策略类型重复"), dupError.getMessage());

        verify(strategyBindingDbService, never()).save(any());
    }
}
