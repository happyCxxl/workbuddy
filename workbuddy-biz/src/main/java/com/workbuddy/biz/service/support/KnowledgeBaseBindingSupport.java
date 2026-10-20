package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import com.workbuddy.common.enums.base.DelFlag;
import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库策略绑定助手：绑定行到 VO 的组装、详情/分页的绑定摘要回填、审计摘要渲染。
 *
 * <p>批量路径统一「一次取绑定行 + 一次取版本」覆盖整页，避免逐行查询的 N+1；
 * 版本被删或停用时不返回半截数据（与单体查询口径一致）。
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseBindingSupport {

    private final WbStrategyBindingDbService strategyBindingDbService;

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;

    /**
     * 查知识库某类型的策略绑定（未绑定返回仅含 strategyType 的空 VO）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param strategyType    策略类型码值
     * @return 绑定视图；未绑定时只含策略类型
     */
    public StrategyBindingVO getBinding(Long knowledgeBaseId, String strategyType) {
        StrategyBindingVO vo = new StrategyBindingVO();
        vo.setStrategyType(strategyType);
        WbStrategyBinding binding = strategyBindingDbService.getByKbAndType(knowledgeBaseId, strategyType);
        if (NullUtil.isNotNull(binding)) {
            WbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
            if (NullUtil.isNotNull(version)) {
                vo.setStrategyVersionId(version.getId());
                vo.setStrategyName(version.getName());
                vo.setStrategyVersion(version.getVersion());
            }
        }
        return vo;
    }

    /**
     * 列出某策略类型下全部知识库的有效绑定。
     *
     * <p>用一条 SQL 取回该策略类型下的全部绑定行（knowledgeBaseIds 传 null = 不限库），
     * 再批量取版本；版本被删或已停用时跳过，不返回半截数据。
     *
     * @param strategyType 策略类型码值
     * @return 每条含 knowledgeBaseId / strategyVersionId / 策略名与版本号；无绑定时空列表
     */
    public List<StrategyBindingVO> listBindings(String strategyType) {
        List<WbStrategyBinding> bindings = strategyBindingDbService.listActiveByTypeAndKbIds(strategyType, null);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<Long, WbPipelineStrategyVersion> versionById = versionsOf(bindings);
        List<StrategyBindingVO> result = new ArrayList<>(bindings.size());
        for (WbStrategyBinding binding : bindings) {
            WbPipelineStrategyVersion version = versionById.get(binding.getStrategyVersionId());
            if (NullUtil.isNull(version)) {
                continue;
            }
            StrategyBindingVO vo = new StrategyBindingVO();
            vo.setKnowledgeBaseId(binding.getKnowledgeBaseId());
            vo.setStrategyType(strategyType);
            vo.setStrategyVersionId(version.getId());
            vo.setStrategyName(version.getName());
            vo.setStrategyVersion(version.getVersion());
            result.add(vo);
        }
        return result;
    }

    /**
     * 详情：填充预处理/切片/向量化策略绑定摘要。
     *
     * @param vo 知识库详情（原地回填三类策略的版本 ID 与摘要）
     */
    public void fillStrategyBindings(KnowledgeBaseVO vo) {
        fillBinding(vo, PreprocessStrategy.TYPE);
        fillBinding(vo, ChunkStrategy.TYPE);
        fillBinding(vo, EmbedStrategy.TYPE);
    }

    /**
     * 分页：批量填充预处理/切片/向量化策略绑定摘要（避免逐行查询）。
     *
     * @param records 当页知识库 VO（原地回填三类策略的版本 ID 与摘要）
     */
    public void fillStrategyBindings(List<KnowledgeBaseVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> kbIds = records.stream().map(KnowledgeBaseVO::getId).toList();
        fillBindingForType(records, kbIds, PreprocessStrategy.TYPE);
        fillBindingForType(records, kbIds, ChunkStrategy.TYPE);
        fillBindingForType(records, kbIds, EmbedStrategy.TYPE);
    }

    /**
     * 绑定摘要（审计用）：null/已删 → 无，版本不存在 → 失效。
     *
     * @param binding 绑定行（可空）
     * @return 审计摘要文本
     */
    public String bindingSummary(WbStrategyBinding binding) {
        if (NullUtil.isNull(binding) || DelFlag.isDeleted(binding.getDelFlag())) {
            return "无";
        }
        WbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
        return NullUtil.isNull(version) ? "失效" : version.getName() + "-" + version.getVersion();
    }

    /**
     * 绑定开关是否关闭（null 视为开启，兼容存量数据）。
     *
     * @param kb 知识库行（可空）
     * @return 开关关闭为 true
     */
    public boolean isBindingDisabled(WbKnowledgeBase kb) {
        return NullUtil.isNotNull(kb) && StrategyBindingSwitch.isOff(kb.getStrategyBindingEnabled());
    }

    private void fillBindingForType(List<KnowledgeBaseVO> records, List<Long> kbIds, String type) {
        Map<Long, WbStrategyBinding> bindingByKb = strategyBindingDbService
                .listActiveByTypeAndKbIds(type, kbIds).stream()
                .collect(Collectors.toMap(WbStrategyBinding::getKnowledgeBaseId, Function.identity(), (a, b) -> a));
        if (bindingByKb.isEmpty()) {
            return;
        }
        Map<Long, WbPipelineStrategyVersion> versionById = versionsOf(bindingByKb.values());
        for (KnowledgeBaseVO vo : records) {
            WbStrategyBinding binding = bindingByKb.get(vo.getId());
            if (NullUtil.isNull(binding)) {
                continue;
            }
            WbPipelineStrategyVersion version = versionById.get(binding.getStrategyVersionId());
            if (NullUtil.isNotNull(version)) {
                applyBinding(vo, version);
            }
        }
    }

    private void fillBinding(KnowledgeBaseVO vo, String type) {
        WbStrategyBinding binding = strategyBindingDbService.getByKbAndType(vo.getId(), type);
        WbPipelineStrategyVersion version = NullUtil.isNull(binding)
                ? null : strategyVersionDbService.getById(binding.getStrategyVersionId());
        if (NullUtil.isNotNull(version)) {
            applyBinding(vo, version);
        }
    }

    /** 批量取策略版本并按 id 建索引（绑定行只取一次版本，避免逐行查的 N+1） */
    private Map<Long, WbPipelineStrategyVersion> versionsOf(Collection<WbStrategyBinding> bindings) {
        List<Long> versionIds = bindings.stream()
                .map(WbStrategyBinding::getStrategyVersionId)
                .distinct()
                .toList();
        return strategyVersionDbService.listByIds(versionIds).stream()
                .collect(Collectors.toMap(WbPipelineStrategyVersion::getId, Function.identity(), (a, b) -> a));
    }

    /** 绑定摘要字段分发：按策略版本行类型落 VO 字段 */
    private void applyBinding(KnowledgeBaseVO vo, WbPipelineStrategyVersion version) {
        String fullVersion = version.getName() + "-" + version.getVersion();
        if (ChunkStrategy.TYPE.equals(version.getType())) {
            vo.setChunkStrategyVersionId(version.getId());
            vo.setChunkStrategyVersion(fullVersion);
        } else if (EmbedStrategy.TYPE.equals(version.getType())) {
            vo.setEmbedStrategyVersionId(version.getId());
            vo.setEmbedStrategyVersion(fullVersion);
        } else {
            vo.setPreprocessStrategyVersionId(version.getId());
            vo.setPreprocessStrategyVersion(fullVersion);
        }
    }
}
