package com.workbuddy.biz.service.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.retrieval.RetrievalCapability;
import com.workbuddy.worker.retrieval.RetrievalRuleSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 检索规则解析/校验器：规则快照 JSON ↔ RetrievalRuleSpec；
 * 注册口径 = 字段白名单 + 取值白名单 + 参数边界 + **预留能力锁定**（未启用能力设非默认值 → 40451）。
 * 执行引擎同样经本类解析，并对锁定能力拒执行（双保险）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalRuleResolver {

    private static final Set<String> TOP_FIELDS = Set.of(
            "channel", "fusion", "preprocess", "rerank", "postprocess", "topK", "scoreThreshold");
    private static final Set<String> FUSION_FIELDS = Set.of("mode", "rrfK", "perChannelLimit");
    private static final Set<String> ACTION_FIELDS = Set.of("mode");

    private static final Set<String> CHANNELS = Set.of(
            RetrievalRuleSpec.CHANNEL_FULLTEXT, RetrievalRuleSpec.CHANNEL_VECTOR, RetrievalRuleSpec.CHANNEL_HYBRID);
    private static final Set<String> FUSION_MODES = Set.of(
            RetrievalRuleSpec.FUSION_RRF, RetrievalRuleSpec.FUSION_WEIGHTED);
    private static final Set<String> PREPROCESS_MODES = Set.of(
            RetrievalRuleSpec.MODE_NONE, RetrievalRuleSpec.MODE_REWRITE, RetrievalRuleSpec.MODE_EXPAND);
    private static final Set<String> RERANK_MODES = Set.of(
            RetrievalRuleSpec.MODE_NONE, RetrievalRuleSpec.MODE_RERANK);
    private static final Set<String> POSTPROCESS_MODES = Set.of(
            RetrievalRuleSpec.MODE_NONE, RetrievalRuleSpec.MODE_PARENT_EXPAND, RetrievalRuleSpec.MODE_NEIGHBOR_EXPAND);

    private final RetrievalCapabilityRegistry capabilityRegistry;

    /**
     * 解析规则快照 JSON（空/非法 JSON → 40001）。
     *
     * @param configSnapshot 规则快照 JSON
     * @return 解析出的规则配置
     */
    public RetrievalRuleSpec parse(String configSnapshot) {
        ThrowUtil.throwIf(StrUtil.isBlank(configSnapshot), ErrorCode.PARAM_INVALID, "检索规则配置不能为空");
        try {
            return JsonUtil.toObject(configSnapshot, RetrievalRuleSpec.class);
        } catch (Exception e) {
            throw new KnowledgeException(ErrorCode.PARAM_INVALID, "检索规则配置不是合法 JSON");
        }
    }

    /**
     * 注册校验：字段/取值白名单 + 参数边界 + 预留能力锁定。违规 40001/40451。
     *
     * @param configSnapshot 规则快照 JSON
     * @return 校验通过的规则配置
     */
    public RetrievalRuleSpec validate(String configSnapshot) {
        RetrievalRuleSpec spec = parse(configSnapshot);
        Map<String, Object> map = JsonUtil.toMap(configSnapshot);

        // ① 字段白名单（Jackson 默认忽略未知字段，此处显式收口拒绝）
        rejectUnknownFields(map, TOP_FIELDS, "检索规则");
        checkNested(map.get("fusion"), FUSION_FIELDS, "fusion");
        checkNested(map.get("preprocess"), ACTION_FIELDS, "preprocess");
        checkNested(map.get("rerank"), ACTION_FIELDS, "rerank");
        checkNested(map.get("postprocess"), ACTION_FIELDS, "postprocess");

        // ② 取值白名单
        ThrowUtil.throwIf(!CHANNELS.contains(spec.getChannel()),
                ErrorCode.PARAM_INVALID, "非法检索通道: " + spec.getChannel());
        ThrowUtil.throwIf(!FUSION_MODES.contains(spec.getFusion().getMode()),
                ErrorCode.PARAM_INVALID, "非法融合模式: " + spec.getFusion().getMode());
        ThrowUtil.throwIf(!PREPROCESS_MODES.contains(spec.getPreprocess().getMode()),
                ErrorCode.PARAM_INVALID, "非法查询预处理模式: " + spec.getPreprocess().getMode());
        ThrowUtil.throwIf(!RERANK_MODES.contains(spec.getRerank().getMode()),
                ErrorCode.PARAM_INVALID, "非法重排模式: " + spec.getRerank().getMode());
        ThrowUtil.throwIf(!POSTPROCESS_MODES.contains(spec.getPostprocess().getMode()),
                ErrorCode.PARAM_INVALID, "非法后处理模式: " + spec.getPostprocess().getMode());

        // ③ 参数边界
        ThrowUtil.throwIf(spec.getTopK() < 1 || spec.getTopK() > 100,
                ErrorCode.PARAM_INVALID, "topK 必须在 1~100 之间");
        ThrowUtil.throwIf(spec.getFusion().getRrfK() < 1,
                ErrorCode.PARAM_INVALID, "rrfK 必须为正整数");
        ThrowUtil.throwIf(spec.getFusion().getPerChannelLimit() < 1 || spec.getFusion().getPerChannelLimit() > 1000,
                ErrorCode.PARAM_INVALID, "每通道召回上限必须在 1~1000 之间");
        ThrowUtil.throwIf(spec.getScoreThreshold() < 0,
                ErrorCode.PARAM_INVALID, "相似度阈值不能为负");

        // ④ 预留能力锁定（未启用能力设非默认值 → 40451，注册侧即拦截）
        requireCapability(RetrievalRuleSpec.FUSION_WEIGHTED.equals(spec.getFusion().getMode()),
                RetrievalCapability.WEIGHTED, "加权融合");
        requireCapability(RetrievalRuleSpec.MODE_REWRITE.equals(spec.getPreprocess().getMode()),
                RetrievalCapability.REWRITE, "查询改写");
        requireCapability(RetrievalRuleSpec.MODE_EXPAND.equals(spec.getPreprocess().getMode()),
                RetrievalCapability.EXPAND, "查询扩展");
        requireCapability(RetrievalRuleSpec.MODE_RERANK.equals(spec.getRerank().getMode()),
                RetrievalCapability.RERANK, "重排");
        requireCapability(RetrievalRuleSpec.MODE_NEIGHBOR_EXPAND.equals(spec.getPostprocess().getMode()),
                RetrievalCapability.NEIGHBOR_EXPAND, "相邻片扩展");
        requireCapability(spec.getScoreThreshold() > 0, RetrievalCapability.SCORE_THRESHOLD, "相似度阈值截断");
        return spec;
    }

    private void requireCapability(boolean configured, RetrievalCapability capability, String label) {
        if (!configured) {
            return;
        }
        ThrowUtil.throwIf(!capabilityRegistry.isEnabled(capability),
                ErrorCode.RETRIEVAL_CAPABILITY_LOCKED, "该检索能力尚未启用: " + label);
    }

    private void rejectUnknownFields(Map<String, Object> map, Set<String> allowed, String scope) {
        for (String key : map.keySet()) {
            ThrowUtil.throwIf(!allowed.contains(key),
                    ErrorCode.PARAM_INVALID, "未知" + scope + "字段: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private void checkNested(Object value, Set<String> allowed, String scope) {
        if (NullUtil.isNull(value)) {
            return;
        }
        ThrowUtil.throwIf(!(value instanceof Map), ErrorCode.PARAM_INVALID, scope + " 必须为对象");
        rejectUnknownFields((Map<String, Object>) value, allowed, scope);
    }
}
