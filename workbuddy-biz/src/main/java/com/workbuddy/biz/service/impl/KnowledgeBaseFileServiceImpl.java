package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.KnowledgeBaseFileService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.biz.service.support.InputVoAssembler;
import com.workbuddy.biz.service.support.KnowledgeBaseAccessSupport;
import com.workbuddy.biz.service.support.TaskVoAssembler;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.input.FileMetadata;
import com.workbuddy.common.domain.input.FileValidationResult;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.domain.rules.StageFunnelRules;
import com.workbuddy.common.domain.storage.StorageRef;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.task.StageStatusVO;
import com.workbuddy.common.enums.input.SubmitStatus;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.provider.StorageRouter;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.input.FileValidatorPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库文档输入用例实现：提交建档、提交记录分页、文件结果分页。
 *
 * <p>提交链路的实现要点：
 * <ul>
 *   <li>幂等：requestId 唯一约束 + 查询返回已有；并发冲突（DuplicateKey）转幂等回放，不报系统错误；</li>
 *   <li>归属失败（知识库不存在/停用/已删）抛 KnowledgeException（40401/40421），不记提交日志；</li>
 *   <li>文件校验不通过（含文件不存在）只记 wb_submit_log(FAIL)，正常返回 FAIL 日志；</li>
 *   <li>文件校验与元数据读取在事务外；建档三写（wb_source_file / wb_file_result / wb_submit_log）
 *       放进短事务（手动逐环节口径：不登记任务，解析由页面手动触发）；</li>
 *   <li>同一 fileId 复用 wb_source_file（uk_file_id；并发撞键时重查复用），每次提交新建 wb_file_result。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseFileServiceImpl implements KnowledgeBaseFileService {

    /** 用户归属默认值（检索强制过滤口径，当前统一 ADMIN） */
    private static final String DEFAULT_OWNER = "ADMIN";

    private final WbSubmitLogDbService submitLogDbService;

    private final WbFileResultDbService kbFileResultDbService;

    private final WbSourceFileDbService sourceFileDbService;

    private final WbPipelineTaskDbService pipelineTaskDbService;

    private final FileValidatorPort fileValidator;

    private final FileStorage fileStorage;

    private final StorageRouter storageRouter;

    private final TransactionTemplate transactionTemplate;

    private final InputVoAssembler inputVoAssembler;

    private final TaskVoAssembler taskVoAssembler;

    private final KnowledgeBaseAccessSupport accessSupport;

    /**
     * 提交文件：幂等检查 → 归属校验 → 文件校验 → 建档三写。
     *
     * <p>校验与元数据读取在事务外；建档三写放进短事务，不登记处理任务。
     * 并发下撞 requestId 唯一键时，事务已回滚，重查并回放已有记录。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求（fileId + requestId）
     * @return 提交响应：新建提交 pipelineTaskId 为 null；幂等回放时回填已有解析任务 ID
     */
    @Override
    public FileSubmitVO submit(Long knowledgeBaseId, FileSubmitDTO request) {
        ThrowUtil.throwIf(StrUtil.isBlank(request.getRequestId()), ErrorCode.REQUEST_ID_MISSING);

        // ① 幂等：同一请求重复到达返回已有记录
        WbSubmitLog existing = submitLogDbService.getByRequestId(request.getRequestId());
        if (NullUtil.isNotNull(existing)) {
            log.info("===> KnowledgeBaseFileServiceImpl submit 幂等回放, requestId={}", request.getRequestId());
            return inputVoAssembler.toSubmitResponse(existing, findParseTaskId(existing.getFileResultId()));
        }

        // ② 校验：存在/未删除 → 归属（非本人可访问的库按"不存在"处理，且先于状态校验，
        //    避免用错误码差异反推出别人某个库是启用还是停用）→ 启用
        WbKnowledgeBase kb = accessSupport.getAccessibleById(knowledgeBaseId);
        KnowledgeBaseRules.checkCanSubmit(kb);

        // ③ 文件校验：读对象存储，必须在事务外；失败只记 FAIL 日志，不建结果、不建任务
        long validationStart = System.currentTimeMillis();
        FileValidationResult validationResult = fileValidator.validate(request.getFileId());
        if (!validationResult.isPassed()) {
            return recordFailAndReturn(knowledgeBaseId, request, validationResult.getFailReason().name(),
                    metadataFileName(request.getFileId()));
        }
        log.info("===> KnowledgeBaseFileServiceImpl submit 文件校验通过, kbId={}, fileId={}, format={}, size={}B,"
                        + " sha256={}, 校验耗时={}ms",
                knowledgeBaseId, request.getFileId(), validationResult.getFormat(),
                validationResult.getSize(), validationResult.getSha256(),
                System.currentTimeMillis() - validationStart);

        // ④ 元数据读取也在事务外；⑤ 建档三写放进短事务（不登记任务——解析由页面触发）
        long buildStart = System.currentTimeMillis();
        FileMetadata metadata = fileStorage.metadata(request.getFileId());
        Long userId = accessSupport.currentUserId();

        WbSubmitLog submitLog;
        try {
            submitLog = Objects.requireNonNull(transactionTemplate.execute(
                    status -> writeSubmission(kb, request, validationResult, metadata, userId)));
        } catch (DuplicateKeyException e) {
            // 并发下同一 requestId 已被处理：事务已回滚，回放已有记录
            WbSubmitLog raced = submitLogDbService.getByRequestId(request.getRequestId());
            if (NullUtil.isNull(raced)) {
                log.warn("===> KnowledgeBaseFileServiceImpl submit 幂等冲突重查为空, requestId={}（唯一键冲突但未查到已有记录）",
                        request.getRequestId());
                throw e;
            }
            log.info("===> KnowledgeBaseFileServiceImpl submit 幂等冲突回放, requestId={}", request.getRequestId());
            return inputVoAssembler.toSubmitResponse(raced, findParseTaskId(raced.getFileResultId()));
        }

        log.info("===> KnowledgeBaseFileServiceImpl submit 建档完成（待手动解析）, kbId={}, fileId={}, fileResultId={},"
                        + " 建档耗时={}ms",
                kb.getId(), request.getFileId(), submitLog.getFileResultId(), System.currentTimeMillis() - buildStart);
        return inputVoAssembler.toSubmitResponse(submitLog, null);
    }

    /**
     * 提交记录分页查询：按知识库分页取 wb_submit_log 并转换为 VO（PASS/FAIL 均含）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（状态/文件名都可留空）
     * @return 提交记录分页 VO
     */
    @Override
    public IPage<SubmitLogVO> pageSubmitLogs(Long knowledgeBaseId, SubmitLogQueryDTO query) {
        return submitLogDbService.pageByKb(query.currentOrDefault(), query.sizeOrDefault(), knowledgeBaseId,
                query.getStatus(), query.getFileName())
                .convert(inputVoAssembler::toSubmitLogVO);
    }

    /**
     * 文件结果分页查询：分页取 wb_file_result，批量装配来源文件信息、存储口径
     * 与五环节（PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED）最新任务状态。
     *
     * <p>stage 参数经 {@link StageFunnelRules#resolveUpstreamStage(String)} 做合法性校验（非法值 40001）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param query           查询条件（环节可留空，页码与页长口径见父类）
     * @return 文件结果分页 VO（含来源文件信息、数据源与各环节状态列表）
     */
    @Override
    public IPage<FileResultVO> pageFileResults(Long knowledgeBaseId, FileResultQueryDTO query) {
        StageFunnelRules.resolveUpstreamStage(query.getStage());
        IPage<WbFileResult> page = kbFileResultDbService.pageByKb(query.currentOrDefault(), query.sizeOrDefault(),
                knowledgeBaseId);
        List<Long> sourceIds = page.getRecords().stream()
                .map(WbFileResult::getSourceFileId)
                .distinct()
                .toList();
        Map<Long, WbSourceFile> sourceMap = sourceIds.isEmpty() ? Map.of()
                : sourceFileDbService.listByIds(sourceIds).stream()
                        .collect(Collectors.toMap(WbSourceFile::getId, Function.identity()));
        IPage<FileResultVO> voPage = page.convert(
                result -> inputVoAssembler.toFileResultVO(result, sourceMap.get(result.getSourceFileId())));

        // 存储口径：所属数据源按来源文件的 fileId 批量取档案，当前启用的数据源取运行时生效值
        List<String> chainFileIds = sourceMap.values().stream()
                .map(WbSourceFile::getFileId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
        Map<String, StorageRef> chainRefs = chainFileIds.isEmpty() ? Map.of()
                : fileStorage.refsOf(chainFileIds);
        Long currentSourceId = storageRouter.currentSourceId();
        StorageType currentType = storageRouter.writeType();
        String currentStorageType = NullUtil.isNull(currentType) ? null : currentType.getCode();
        String currentStorageSourceName = storageRouter.currentName();
        voPage.getRecords().forEach(vo -> {
            StorageRef ref = NullUtil.isNull(vo.getFileId()) ? null : chainRefs.get(vo.getFileId());
            Long archiveSourceId = NullUtil.isNull(ref) ? null : ref.sourceId();
            vo.setStorageType(NullUtil.isNull(ref) ? null : ref.storageType());
            vo.setStorageSourceId(archiveSourceId);
            vo.setStorageSourceName(storageSourceName(archiveSourceId));
            vo.setCurrentStorageSourceId(currentSourceId);
            vo.setCurrentStorageType(currentStorageType);
            vo.setCurrentStorageSourceName(currentStorageSourceName);
        });

        // 环节状态：列表行附各环节最新任务状态（PARSE→STRUCTURE→PREPROCESS→CHUNK→EMBED 五环节）
        List<Long> resultIds = voPage.getRecords().stream().map(FileResultVO::getId).toList();
        // ① 每环节一次 in 查询（id 倒序；按 fileResultId 去重取首条即最新任务）
        List<String> statusStages = List.of(PipelineStage.PARSE.name(), PipelineStage.STRUCTURE.name(),
                PipelineStage.PREPROCESS.name(), PipelineStage.CHUNK.name(), PipelineStage.EMBED.name());
        Map<String, Map<Long, WbPipelineTask>> stageTaskMaps = new HashMap<>();
        for (String statusStage : statusStages) {
            Map<Long, WbPipelineTask> byFileResult = resultIds.isEmpty() ? Map.of()
                    : pipelineTaskDbService.listByFileResultIdsAndStage(resultIds, statusStage).stream()
                            .collect(Collectors.toMap(WbPipelineTask::getFileResultId, Function.identity(),
                                    (first, second) -> first));
            stageTaskMaps.put(statusStage, byFileResult);
        }
        // ② 按环节顺序装配状态列表（无任务的环节不出现在列表中）
        voPage.getRecords().forEach(vo -> {
            List<StageStatusVO> statuses = new ArrayList<>();
            for (String statusStage : statusStages) {
                WbPipelineTask task = stageTaskMaps.get(statusStage).get(vo.getId());
                if (NullUtil.isNotNull(task)) {
                    statuses.add(taskVoAssembler.toStageStatusVO(task));
                }
            }
            if (!statuses.isEmpty()) {
                vo.setStageStatuses(statuses);
            }
        });
        return voPage;
    }

    /**
     * 数据源显示名：取注册表里的名称，取不到（ID 为空或数据源未注册）返回 null。
     *
     * @param sourceId 数据源 ID（可空）
     * @return 数据源显示名；ID 为空或未注册时为 null
     */
    private String storageSourceName(Long sourceId) {
        return NullUtil.isNull(sourceId) ? null : storageRouter.nameOf(sourceId);
    }

    /**
     * 建档三写（同一事务内）：来源文件复用/新建 → 文件结果 → 提交日志。
     *
     * <p>调用方用 {@link TransactionTemplate} 包住本方法；提交日志撞 uk_request_id 时异常向上抛，
     * 由事务模板回滚整组写入。
     *
     * @param kb               知识库
     * @param request          提交请求
     * @param validationResult 文件校验结果
     * @param metadata         文件元数据（事务外读取）
     * @param userId           当前用户 ID（可空）
     * @return 已落库的提交日志
     */
    private WbSubmitLog writeSubmission(WbKnowledgeBase kb, FileSubmitDTO request,
                                        FileValidationResult validationResult, FileMetadata metadata, Long userId) {
        WbSourceFile sourceFile = findOrCreateSourceFile(request, validationResult, metadata, userId);

        WbFileResult fileResult = new WbFileResult();
        fileResult.setKnowledgeBaseId(kb.getId());
        fileResult.setOwner(DEFAULT_OWNER);
        fileResult.setSourceFileId(sourceFile.getId());
        fileResult.setUserId(userId);
        kbFileResultDbService.save(fileResult);

        WbSubmitLog submitLog = new WbSubmitLog();
        submitLog.setKnowledgeBaseId(kb.getId());
        submitLog.setRequestId(request.getRequestId());
        submitLog.setFileId(request.getFileId());
        submitLog.setSha256(validationResult.getSha256());
        submitLog.setFileName(metadata.getFileName());
        submitLog.setFileResultId(fileResult.getId());
        submitLog.setStatus(SubmitStatus.PASS.name());
        submitLog.setUserId(userId);
        submitLogDbService.save(submitLog);
        return submitLog;
    }

    /** 按 fileId 复用来源文件；并发撞 uk_file_id 时重查复用（同文件不重复建档）。 */
    private WbSourceFile findOrCreateSourceFile(FileSubmitDTO request, FileValidationResult validationResult,
                                                FileMetadata metadata, Long userId) {
        WbSourceFile sourceFile = sourceFileDbService.findByFileId(request.getFileId());
        if (NullUtil.isNotNull(sourceFile)) {
            log.info("===> KnowledgeBaseFileServiceImpl submit 复用来源文件, fileId={}, sourceFileId={}",
                    request.getFileId(), sourceFile.getId());
            return sourceFile;
        }
        sourceFile = new WbSourceFile();
        sourceFile.setFileId(request.getFileId());
        sourceFile.setSha256(validationResult.getSha256());
        sourceFile.setFileName(metadata.getFileName());
        sourceFile.setMimeType(validationResult.getMimeType());
        sourceFile.setFileSize(validationResult.getSize());
        sourceFile.setInputSnapshot(JsonUtil.toJsonStr(metadata));
        sourceFile.setUserId(userId);
        try {
            sourceFileDbService.save(sourceFile);
        } catch (DuplicateKeyException e) {
            WbSourceFile raced = sourceFileDbService.findByFileId(request.getFileId());
            if (NullUtil.isNull(raced)) {
                throw e;
            }
            log.info("===> KnowledgeBaseFileServiceImpl submit 并发复用来源文件, fileId={}, sourceFileId={}",
                    request.getFileId(), raced.getId());
            return raced;
        }
        return sourceFile;
    }

    /**
     * 校验失败：只记 FAIL 日志一条，不建结果；文件名由调用方在事务外尽力取，取不到存空串（列 NOT NULL）。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param request         提交请求
     * @param failReason      校验失败原因
     * @param fileName        文件名（可空）
     * @return 失败提交响应
     */
    private FileSubmitVO recordFailAndReturn(Long knowledgeBaseId, FileSubmitDTO request, String failReason,
                                             String fileName) {
        WbSubmitLog failLog = new WbSubmitLog();
        failLog.setKnowledgeBaseId(knowledgeBaseId);
        failLog.setRequestId(request.getRequestId());
        failLog.setFileId(request.getFileId());
        failLog.setFileName(fileName);
        // sha256 列 NOT NULL 无默认值；校验失败（尤其文件不存在）算不出指纹，存空串占位
        failLog.setSha256("");
        failLog.setStatus(SubmitStatus.FAIL.name());
        failLog.setFailReason(failReason);
        failLog.setUserId(accessSupport.currentUserId());
        submitLogDbService.save(failLog);
        log.info("===> KnowledgeBaseFileServiceImpl submit 校验失败, kbId={}, fileId={}, fileName={}, failReason={}",
                knowledgeBaseId, request.getFileId(), fileName, failReason);
        return inputVoAssembler.toSubmitResponse(failLog, null);
    }

    /** 校验失败时尽力取文件名；文件不存在等场景返回空串。 */
    private String metadataFileName(String fileId) {
        try {
            FileMetadata metadata = fileStorage.metadata(fileId);
            return StrUtil.blankToDefault(metadata.getFileName(), "");
        } catch (Exception e) {
            log.debug("元数据不可得, 文件名留空, fileId={}", fileId);
            return "";
        }
    }

    /** 查文件结果已有的 PARSE 任务 ID（幂等回放响应用；fileResultId 为空或尚无任务返回 null）。 */
    private Long findParseTaskId(Long fileResultId) {
        if (NullUtil.isNull(fileResultId)) {
            return null;
        }
        WbPipelineTask task = pipelineTaskDbService.getByFileResultIdAndStage(fileResultId, PipelineStage.PARSE.name());
        return NullUtil.isNull(task) ? null : task.getId();
    }
}
