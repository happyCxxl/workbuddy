package com.workbuddy.biz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.enums.user.UserRole;
import com.workbuddy.common.security.KnowledgeUser;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.when;

/**
 * 知识库服务单测共享夹具：登录态、实体/DTO 构造、分页与策略绑定夹具。
 *
 * <p>每个用例类按被测服务只声明实际用到的 mock，夹具只提供跨用例复用的纯构造与登录逻辑。
 */
abstract class KnowledgeBaseTestFixture {

    /** 当前登录用户（普通用户）：夹具知识库默认都归它，正向用例才能过归属校验 */
    protected static final long ME = 1001L;

    /** 另一个用户：用于「看不到别人的库」的反向用例 */
    protected static final long OTHER = 2002L;

    /**
     * 收尾清理线程级安全上下文，避免登录态串到下一个用例。
     */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 设置登录上下文（服务层归属校验直接读 Spring Security 上下文）。
     *
     * @param userId 用户 ID
     * @param role   用户角色
     */
    protected void login(long userId, UserRole role) {
        KnowledgeUser user = new KnowledgeUser();
        user.setId(userId);
        user.setUsername("u" + userId);
        user.setRole(role);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    /**
     * 知识库实体夹具。
     *
     * @param id     知识库 ID
     * @param status 状态码
     * @return 默认归当前登录用户的知识库
     */
    protected WbKnowledgeBase kb(long id, int status) {
        WbKnowledgeBase k = new WbKnowledgeBase();
        k.setId(id);
        k.setName("库" + id);
        k.setStatus(status);
        // 归属：本夹具统一归当前登录用户，反向用例另行 setUserId(OTHER)
        k.setUserId(ME);
        return k;
    }

    /**
     * 有效绑定行夹具（KB 2 绑定 CHUNK）。
     *
     * @param versionId 策略版本 ID
     * @return 绑定行
     */
    protected WbStrategyBinding binding(Long versionId) {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setId(1L);
        binding.setKnowledgeBaseId(2L);
        binding.setStrategyType("CHUNK");
        binding.setStrategyVersionId(versionId);
        return binding;
    }

    /**
     * 启用中的 CHUNK 策略版本行夹具。
     *
     * @return 策略版本行
     */
    protected WbPipelineStrategyVersion chunkVersion() {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(66L);
        row.setType("CHUNK");
        row.setName("chunk-hybrid");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        return row;
    }

    /**
     * 批量绑定请求项夹具。
     *
     * @param type      策略类型
     * @param versionId 策略版本 ID
     * @return 绑定项
     */
    protected StrategyBindingsUpdateDTO.StrategyBindItem bindItem(String type, Long versionId) {
        StrategyBindingsUpdateDTO.StrategyBindItem item =
                new StrategyBindingsUpdateDTO.StrategyBindItem();
        item.setStrategyType(type);
        item.setStrategyVersionId(versionId);
        return item;
    }

    /**
     * 批量绑定请求夹具。
     *
     * @param items 绑定项
     * @return 批量绑定请求
     */
    protected StrategyBindingsUpdateDTO bindRequest(StrategyBindingsUpdateDTO.StrategyBindItem... items) {
        StrategyBindingsUpdateDTO request = new StrategyBindingsUpdateDTO();
        request.setBindings(List.of(items));
        return request;
    }

    /**
     * 让指定类型的策略版本查询返回启用中的行。
     *
     * @param service   策略版本数据访问替身
     * @param type      策略类型
     * @param versionId 策略版本 ID
     */
    protected void stubActiveVersions(WbPipelineStrategyVersionDbService service, String type, Long versionId) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(versionId);
        row.setType(type);
        row.setName(type.toLowerCase() + "-x");
        row.setVersion("v1");
        row.setStatus("ACTIVE");
        when(service.getById(versionId)).thenReturn(row);
    }

    /**
     * 分页结果夹具（单页、总数等于行数）。
     *
     * @param rows 记录行
     * @return 知识库分页
     */
    protected Page<WbKnowledgeBase> kbPage(WbKnowledgeBase... rows) {
        Page<WbKnowledgeBase> page = new Page<>(1, 10);
        page.setRecords(List.of(rows));
        page.setTotal(rows.length);
        return page;
    }

    /**
     * 绑定行夹具（指定库与类型）。
     *
     * @param kbId      知识库 ID
     * @param type      策略类型
     * @param versionId 策略版本 ID
     * @return 绑定行
     */
    protected WbStrategyBinding bindingOf(Long kbId, String type, Long versionId) {
        WbStrategyBinding binding = new WbStrategyBinding();
        binding.setKnowledgeBaseId(kbId);
        binding.setStrategyType(type);
        binding.setStrategyVersionId(versionId);
        return binding;
    }

    /**
     * 策略版本行夹具。
     *
     * @param id      版本行 ID
     * @param type    策略类型
     * @param name    策略名
     * @param version 版本号
     * @return 启用中的策略版本行
     */
    protected WbPipelineStrategyVersion versionOf(Long id, String type, String name, String version) {
        WbPipelineStrategyVersion row = new WbPipelineStrategyVersion();
        row.setId(id);
        row.setType(type);
        row.setName(name);
        row.setVersion(version);
        row.setStatus("ACTIVE");
        return row;
    }

    /**
     * 知识库分页查询条件夹具（页码 1 / 页长 10 由缺省口径兜底）。
     *
     * @param name   名称（可空）
     * @param status 状态（可空）
     * @param sort   排序（可空）
     * @return 查询条件
     */
    protected KnowledgeBaseQueryDTO kbQuery(String name, Integer status, String sort) {
        KnowledgeBaseQueryDTO query = new KnowledgeBaseQueryDTO();
        query.setName(name);
        query.setStatus(status);
        query.setSort(sort);
        return query;
    }
}
