package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.biz.testkit.SecurityTestSupport;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 策略绑定批量查询单测。
 *
 * <p>守护 {@code strategyBindings} 的三条口径：类型白名单校验、
 * 版本被删/停用时跳过不返回半截数据、全量取绑定行（knowledgeBaseIds 传 null）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeBaseStrategyBindingsTest {

    @Mock
    private WbKnowledgeBaseDbService knowledgeBaseDbService;

    @Mock
    private WbAuditLogDbService kbAuditLogDbService;

    @Mock
    private WbStrategyBindingDbService strategyBindingDbService;

    @Mock
    private WbPipelineStrategyVersionDbService strategyVersionDbService;

    @Mock
    private WbFileResultDbService kbFileResultDbService;

    @Mock
    private WbPipelineTaskDbService pipelineTaskDbService;

    @Mock
    private FileStorage fileStorage;

    private MockedStatic<SecurityUtil> security;

    private KnowledgeBaseServiceImpl service;

    /**
     * 每个用例重建管理员登录态与服务。
     */
    @BeforeEach
    void setUp() {
        security = Mockito.mockStatic(SecurityUtil.class);
        SecurityTestSupport.loginAdmin();
        service = new KnowledgeBaseServiceImpl(knowledgeBaseDbService, kbAuditLogDbService,
                strategyBindingDbService, strategyVersionDbService, kbFileResultDbService,
                mock(com.workbuddy.biz.service.db.WbIndexSetDbService.class),
                mock(com.workbuddy.biz.service.db.WbIndexVersionDbService.class),
                mock(WbSourceFileDbService.class),
                mock(com.workbuddy.biz.service.db.WbSubmitLogDbService.class),
                pipelineTaskDbService,
                mock(com.workbuddy.worker.input.FileValidatorPort.class),
                fileStorage,
                mock(com.workbuddy.filecenter.provider.StorageRouter.class),
                new InputVoAssembler(), new TaskVoAssembler());
    }

    /**
     * 收尾关闭静态桩。
     */
    @AfterEach
    void tearDown() {
        security.close();
    }

    /**
     * 未知策略类型被拒绝。
     *
     * <p>守护 {@code strategyBindings} 的类型白名单（错误码 40001）。
     */
    @Test
    void unknownTypeShouldReject() {
        KnowledgeException ex = assertThrows(KnowledgeException.class,
                () -> service.strategyBindings("NO_SUCH"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /**
     * 无绑定时返回空列表。
     *
     * <p>守护 {@code strategyBindings} 的空集短路。
     */
    @Test
    void emptyBindingsShouldReturnEmptyList() {
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", null)).thenReturn(List.of());

        assertTrue(service.strategyBindings("CHUNK").isEmpty());
    }

    /**
     * 版本被删/停用时不返回半截数据，只返回版本完整的行。
     *
     * <p>守护 {@code strategyBindings} 的版本过滤：把无版本的行也返回即失败。
     */
    @Test
    void shouldSkipBindingsWithoutVersion() {
        WbStrategyBinding orphan = binding(100L, 1L, 999L);
        WbStrategyBinding valid = binding(101L, 2L, 500L);
        when(strategyBindingDbService.listActiveByTypeAndKbIds("CHUNK", null))
                .thenReturn(List.of(orphan, valid));
        WbPipelineStrategyVersion version = version(500L, "chunk-hybrid", "v1");
        when(strategyVersionDbService.listByIds(any())).thenReturn(List.of(version));

        List<StrategyBindingVO> result = service.strategyBindings("CHUNK");

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getKnowledgeBaseId());
        assertEquals("chunk-hybrid", result.get(0).getStrategyName());
    }

    /**
     * 构造绑定行。
     */
    private WbStrategyBinding binding(Long id, Long kbId, Long versionId) {
        WbStrategyBinding b = new WbStrategyBinding();
        b.setId(id);
        b.setKnowledgeBaseId(kbId);
        b.setStrategyType("CHUNK");
        b.setStrategyVersionId(versionId);
        return b;
    }

    /**
     * 构造版本行。
     */
    private WbPipelineStrategyVersion version(Long id, String name, String ver) {
        WbPipelineStrategyVersion v = new WbPipelineStrategyVersion();
        v.setId(id);
        v.setType("CHUNK");
        v.setName(name);
        v.setVersion(ver);
        v.setStatus("ACTIVE");
        return v;
    }
}
