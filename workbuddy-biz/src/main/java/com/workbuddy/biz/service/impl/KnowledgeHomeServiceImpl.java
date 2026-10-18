package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.service.KnowledgeHomeService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.dto.request.home.HomeRecentSubmitQueryDTO;
import com.workbuddy.common.dto.response.home.HomeRecentSubmitVO;
import com.workbuddy.common.dto.response.home.HomeSummaryVO;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.enums.input.FileValidationFailLabels;
import com.workbuddy.common.enums.knowledge.KnowledgeBaseStatus;
import com.workbuddy.common.enums.strategy.StrategyType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 首页服务实现：资产速览 + 最近提交。
 *
 * <p>两点实现口径：
 *
 * <ol>
 *   <li>**可见范围在服务端定**：最近提交只查当前登录用户（`wb_submit_log.user_id`），
 *       接口不接受"提交人"参数，避免前端传谁就查谁；</li>
 *   <li>**批量反查、不逐行查库**：知识库名与文件类型都是先收集本页涉及的 ID，
 *       各查一次批量取回，再装配 VO —— 一次翻页最多 2 次附加查询，与页大小无关。</li>
 * </ol>
 *
 * <p>所有码值都走枚举（{@link StrategyType} / {@link FileFormat} / {@link KnowledgeBaseStatus}），
 * 本类不持有字符串常量与映射表。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeHomeServiceImpl implements KnowledgeHomeService {

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;
    private final WbPipelineStrategyVersionDbService strategyVersionDbService;
    private final WbFileResultDbService fileResultDbService;
    private final WbSubmitLogDbService submitLogDbService;
    private final WbSourceFileDbService sourceFileDbService;

    @Override
    public HomeSummaryVO summary() {
        HomeSummaryVO vo = new HomeSummaryVO();
        // 可见范围与知识库列表页同一口径：普通用户只统计自己创建的库，管理员统计全部。
        // 不这样做的话，新账号会看到「知识库 0 / 文档 1284」——数字来自别人的库。
        Long ownerId = KnowledgeBaseRules.visibleOwnerId(SecurityUtil.getUser());
        vo.setKnowledgeBaseCount(knowledgeBaseDbService.countByStatus(null, ownerId));
        vo.setEnabledKnowledgeBaseCount(
                knowledgeBaseDbService.countByStatus(KnowledgeBaseStatus.ACTIVE.getCode(), ownerId));
        // 可用策略 = 四类启用中的策略版本计数（type + status = ACTIVE）
        long preprocess = strategyVersionDbService.countEnabledByType(StrategyType.PREPROCESS);
        long chunk = strategyVersionDbService.countEnabledByType(StrategyType.CHUNK);
        long embed = strategyVersionDbService.countEnabledByType(StrategyType.EMBED);
        long retrieval = strategyVersionDbService.countEnabledByType(StrategyType.RETRIEVAL);
        vo.setPreprocessVersionCount(preprocess);
        vo.setChunkVersionCount(chunk);
        vo.setEmbedVersionCount(embed);
        vo.setRetrievalVersionCount(retrieval);
        vo.setStrategyVersionCount(preprocess + chunk + embed + retrieval);
        // 文档数 = 可见库下的 wb_file_result 行数：**文件校验失败的提交不建结果**（只记 wb_submit_log(FAIL)）
        vo.setDocumentCount(
                fileResultDbService.countByKbIds(knowledgeBaseDbService.listIdsByOwner(ownerId)));
        return vo;
    }

    @Override
    public IPage<HomeRecentSubmitVO> recentSubmits(HomeRecentSubmitQueryDTO query) {
        long current = query.currentOrDefault();
        long size = query.sizeOrDefault();
        KnowledgeUser user = SecurityUtil.getUser();
        // 该接口本身要求认证；走到这里说明安全上下文缺失，宁可不给数据也不放全量
        ThrowUtil.throwIf(NullUtil.isNull(user), ErrorCode.UNAUTHORIZED);

        IPage<WbSubmitLog> page = submitLogDbService.pageRecentByUser(current, size, user.getId());
        List<WbSubmitLog> rows = page.getRecords();
        Page<HomeRecentSubmitVO> result = new Page<>(current, size, page.getTotal());
        if (rows.isEmpty()) {
            return result;
        }

        Map<Long, WbKnowledgeBase> kbById = loadKnowledgeBases(rows);
        Map<String, String> mimeTypeByFileId = loadMimeTypes(rows);
        List<HomeRecentSubmitVO> vos = new ArrayList<>(rows.size());
        for (WbSubmitLog row : rows) {
            vos.add(toRecentSubmitVO(row, kbById, mimeTypeByFileId));
        }
        result.setRecords(vos);
        return result;
    }

    /** 提交行 → VO：补知识库名与文件类型，失败原因翻译成中文 */
    private HomeRecentSubmitVO toRecentSubmitVO(WbSubmitLog row, Map<Long, WbKnowledgeBase> kbById,
                                               Map<String, String> mimeTypeByFileId) {
        HomeRecentSubmitVO vo = new HomeRecentSubmitVO();
        vo.setId(row.getId());
        vo.setFileName(row.getFileName());
        vo.setKnowledgeBaseId(row.getKnowledgeBaseId());
        WbKnowledgeBase kb = NullUtil.isNull(row.getKnowledgeBaseId())
                ? null : kbById.get(row.getKnowledgeBaseId());
        vo.setKnowledgeBaseName(NullUtil.isNotNull(kb)
                ? kb.getName() : "知识库 " + row.getKnowledgeBaseId());
        vo.setFileType(resolveFileType(row.getFileId(), mimeTypeByFileId));
        vo.setStatus(row.getStatus());
        vo.setFailReason(row.getFailReason());
        vo.setFailReasonLabel(FileValidationFailLabels.label(row.getFailReason()));
        vo.setCreateTime(row.getCreateTime());
        return vo;
    }

    /**
     * 文件类型：由来源文件的 MIME 换算成格式枚举名。
     *
     * <p>**不按扩展名猜**（格式的唯一口径是 Tika 魔数识别结果）；校验失败的行不建来源文件，
     * 取不到 MIME 就返回 null，界面显示占位符。
     */
    private String resolveFileType(String fileId, Map<String, String> mimeTypeByFileId) {
        String mimeType = StrUtil.isBlank(fileId) ? null : mimeTypeByFileId.get(fileId);
        FileFormat format = StrUtil.isBlank(mimeType) ? null : FileFormat.ofMimeType(mimeType);
        return NullUtil.isNull(format) ? null : format.name();
    }

    /** 收集本页提交记录涉及的知识库 ID → 实体 */
    private Map<Long, WbKnowledgeBase> loadKnowledgeBases(List<WbSubmitLog> rows) {
        Set<Long> ids = new HashSet<>();
        for (WbSubmitLog row : rows) {
            if (NullUtil.isNotNull(row.getKnowledgeBaseId())) {
                ids.add(row.getKnowledgeBaseId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, WbKnowledgeBase> map = new HashMap<>();
        for (WbKnowledgeBase kb : knowledgeBaseDbService.listByIds(ids)) {
            map.put(kb.getId(), kb);
        }
        return map;
    }

    /** 收集本页提交记录涉及的文件 ID → MIME（文件类型展示用，一次批量查询） */
    private Map<String, String> loadMimeTypes(List<WbSubmitLog> rows) {
        Set<String> fileIds = new HashSet<>();
        for (WbSubmitLog row : rows) {
            if (StrUtil.isNotBlank(row.getFileId())) {
                fileIds.add(row.getFileId());
            }
        }
        if (fileIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (WbSourceFile sourceFile : sourceFileDbService.listByFileIds(fileIds)) {
            map.put(sourceFile.getFileId(), sourceFile.getMimeType());
        }
        return map;
    }
}
