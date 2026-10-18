package com.workbuddy.biz.service.support;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 处理链存储口径：一条链的源文件落在哪个数据源，与当前启用的数据源是否一致。
 *
 * <p>链的数据源取「文件结果 → 来源文件 → 文件档案」这条链。环节触发与产物写入两处入口共用本类，
 * 不一致时拒绝执行，同一条链的产物不会落到另一个数据源。
 *
 * <p>链路缺环、档案行缺失、档案未记数据源或当前没有启用的数据源时都不判定为不一致：这些情况取不到链的
 * 数据源，交由执行与读取阶段按记录给出更明确的错误码。
 */
@Component
@RequiredArgsConstructor
public class ChainStorageSupport {

    private final WbFileResultDbService fileResultDbService;
    private final WbSourceFileDbService sourceFileDbService;
    private final FileStorage fileStorage;
    private final StorageRouter storageRouter;

    /**
     * 处理链的数据源 ID。
     *
     * @param fileResultId 文件结果 ID
     * @return 源文件档案的数据源 ID；链路缺环、fileId 为空或档案无数据源返回 null
     */
    public Long chainSourceIdOf(Long fileResultId) {
        WbFileResult fileResult = fileResultDbService.getById(fileResultId);
        if (NullUtil.isNull(fileResult) || NullUtil.isNull(fileResult.getSourceFileId())) {
            return null;
        }
        WbSourceFile sourceFile = sourceFileDbService.getById(fileResult.getSourceFileId());
        return NullUtil.isNull(sourceFile) ? null : storageSourceIdOfFile(sourceFile.getFileId());
    }

    /**
     * 批量取处理链的数据源 ID：文件结果与来源文件各按一次 in 查询取回，档案一次批量取。
     *
     * @param fileResultIds 文件结果 ID 集合
     * @return 文件结果 ID → 链的数据源 ID；链路缺环与档案无数据源的结果 ID 不出现在映射中
     */
    public Map<Long, Long> chainSourceIdsOf(Collection<Long> fileResultIds) {
        List<Long> distinctIds = fileResultIds.stream()
                .filter(NullUtil::isNotNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        List<WbFileResult> fileResults = fileResultDbService.listByIds(distinctIds);
        List<Long> sourceIds = fileResults.stream()
                .map(WbFileResult::getSourceFileId)
                .filter(NullUtil::isNotNull)
                .distinct()
                .toList();
        Map<Long, WbSourceFile> sourceFiles = sourceIds.isEmpty() ? Map.of()
                : sourceFileDbService.listByIds(sourceIds).stream()
                        .collect(Collectors.toMap(WbSourceFile::getId, Function.identity()));
        List<String> fileIds = sourceFiles.values().stream()
                .map(WbSourceFile::getFileId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, StorageRef> archiveRefs = fileIds.isEmpty() ? Map.of() : fileStorage.refsOf(fileIds);
        Map<Long, Long> chainSourceIds = new HashMap<>();
        fileResults.forEach(fileResult -> {
            WbSourceFile sourceFile = sourceFiles.get(fileResult.getSourceFileId());
            StorageRef ref = NullUtil.isNull(sourceFile) ? null : archiveRefs.get(sourceFile.getFileId());
            if (NullUtil.isNotNull(ref) && NullUtil.isNotNull(ref.sourceId())) {
                chainSourceIds.put(fileResult.getId(), ref.sourceId());
            }
        });
        return chainSourceIds;
    }

    /**
     * 文件档案的数据源 ID。
     *
     * @param fileId 文件 ID（可空）
     * @return 档案的数据源 ID；fileId 为空、档案不存在或档案未记数据源返回 null
     */
    public Long storageSourceIdOfFile(String fileId) {
        if (StrUtil.isBlank(fileId)) {
            return null;
        }
        try {
            StorageRef ref = fileStorage.refOf(fileId);
            return NullUtil.isNull(ref) ? null : ref.sourceId();
        } catch (KnowledgeException e) {
            // 档案不存在：取不到链的数据源，交由读取阶段给出明确错误码
            ErrorCode code = e.getErrorCode();
            if (code == ErrorCode.FILE_NOT_FOUND || code == ErrorCode.STORAGE_BACKEND_UNCONFIGURED) {
                return null;
            }
            throw e;
        }
    }

    /**
     * 链的数据源与当前启用的数据源是否已证实不一致。
     *
     * @param fileResultId 文件结果 ID
     * @return 两侧数据源都取到且不同则为 true
     */
    public boolean storageMismatch(Long fileResultId) {
        return mismatch(chainSourceIdOf(fileResultId), currentSourceId());
    }

    /**
     * 档案的数据源与当前启用的数据源是否已证实不一致。
     *
     * @param archiveSourceId 档案的数据源 ID（可空）
     * @return 两侧数据源都取到且不同则为 true
     */
    public boolean storageMismatchOfArchive(Long archiveSourceId) {
        return mismatch(archiveSourceId, currentSourceId());
    }

    /**
     * 存储一致性校验：不一致时拒绝执行。
     *
     * @param fileResultId 文件结果 ID
     * @throws KnowledgeException 链的数据源与当前启用的数据源不一致（40453）
     */
    public void requireStorageMatch(Long fileResultId) {
        Long chainSourceId = chainSourceIdOf(fileResultId);
        Long currentSourceId = currentSourceId();
        ThrowUtil.throwIf(mismatch(chainSourceId, currentSourceId), ErrorCode.STORAGE_TYPE_MISMATCH,
                "该任务属于 " + sourceName(chainSourceId) + "，当前启用的是 " + sourceName(currentSourceId)
                        + "，无法继续执行");
    }

    /**
     * 当前启用的数据源 ID（随数据源切换热替换）
     */
    public Long currentSourceId() {
        return storageRouter.currentSourceId();
    }

    /**
     * 当前启用的数据源显示名
     */
    public String currentSourceName() {
        return sourceName(currentSourceId());
    }

    /**
     * 数据源显示名：未注册的 ID 回落该数据源的存储类型显示名，取不到类型时带上 ID
     */
    public String sourceName(Long sourceId) {
        if (NullUtil.isNull(sourceId)) {
            return "未记录的数据源";
        }
        String name = storageRouter.nameOf(sourceId);
        if (StrUtil.isNotBlank(name)) {
            return name;
        }
        StorageType type = storageRouter.typeOf(sourceId);
        return NullUtil.isNull(type) ? "数据源#" + sourceId : storageName(type);
    }

    /**
     * 存储类型显示名（新增存储类型时这里补一行）
     */
    public static String storageName(StorageType type) {
        return switch (type) {
            case MINIO -> "MinIO";
            case LOCAL -> "本地";
        };
    }

    private boolean mismatch(Long archiveSourceId, Long currentSourceId) {
        return NullUtil.isNotNull(archiveSourceId) && NullUtil.isNotNull(currentSourceId)
                && !archiveSourceId.equals(currentSourceId);
    }
}
