package com.workbuddy.common.convert;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexSet;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStepLog;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.rules.PreprocessViewRules;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.task.StepLogFields;
import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.response.chunk.ChunkItemVO;
import com.workbuddy.common.dto.response.embed.EmbedRecordItemVO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessCellVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessElementVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessFieldVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTraceVO;
import com.workbuddy.common.dto.response.stagecontent.BBoxVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.dto.response.structure.StructureCellVO;
import com.workbuddy.common.dto.response.structure.StructureConflictVO;
import com.workbuddy.common.dto.response.structure.StructureOutlineVO;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.common.enums.index.IndexVersionStatus;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.InheritConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 知识库域转换：入参（请求 → 实体）与出参（实体/产物 → VO）统一收口在本接口。
 *
 * <p>编译期生成实现、运行期无反射；公共字段忽略清单继承自
 * {@link BaseInfoConvertConfig#serverFields}（仅继承 {@code BaseInfo} 的实体使用）。
 * 只搬字段，不查库、不算统计（{`JAVA-DTO-013`}）。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface KnowledgeConverter {

    // ===== ChunkConverter =====

    /**
     * 单条切片行 → 切片条目 VO（产物侧字段留空，由调用方补齐）。
     *
     * @param chunk 切片行
     * @return 切片条目 VO
     */
    @Mapping(target = "sourceElementCount", ignore = true)
    @Mapping(target = "fallbackReason", ignore = true)
    ChunkItemVO mapChunk(WbChunk chunk);

    /**
     * 切片行 → 切片条目 VO 列表：来源元素个数与兜底原因按 chunkId 从产物补齐。
     *
     * @param chunks        切片行（DB）
     * @param productChunks 切片产物里的片（键 = chunkId）
     * @return 切片条目 VO 列表
     */
    default List<ChunkItemVO> toChunkItemVOs(List<WbChunk> chunks, Map<String, Chunk> productChunks) {
        return ObjectUtil.defaultIfNull(chunks, new ArrayList<WbChunk>()).stream()
                .map(chunk -> {
                    ChunkItemVO vo = mapChunk(chunk);
                    Chunk productChunk = NullUtil.isNull(productChunks) || NullUtil.isNull(chunk.getChunkId()) ?
                            null : productChunks.get(chunk.getChunkId());
                    if (NullUtil.isNotNull(productChunk)) {
                        vo.setSourceElementCount(ObjectUtil.defaultIfNull(productChunk.getSourceElementIds(),
                                new ArrayList<String>()).size());
                        vo.setFallbackReason(productChunk.getFallbackReason());
                    }
                    return vo;
                })
                .toList();
    }

    // ===== EmbedConverter =====

    /**
     * 记录行转记录条目 VO 列表。
     *
     * @param records 向量记录列表
     * @return 记录条目 VO 列表
     */
    List<EmbedRecordItemVO> toRecordItemVOs(List<WbEmbeddingRecord> records);

    /**
     * 单条记录行转记录条目 VO。
     *
     * @param record 向量记录行
     * @return 记录条目 VO
     */
    EmbedRecordItemVO toRecordItemVO(WbEmbeddingRecord record);


    // ===== InputConverter =====

    /**
     * 提交日志实体 → 提交记录 VO（字段同名一一映射）。
     *
     * @param log 提交日志实体
     * @return 提交记录 VO
     */
    SubmitLogVO toSubmitLogVO(WbSubmitLog log);

    /**
     * 文件结果实体 + 来源文件 → 文件结果 VO：来源文件字段按前缀取值，缺失时留空。
     *
     * @param result     文件结果实体
     * @param sourceFile 来源文件实体（可空）
     * @return 文件结果 VO
     */
    @Mapping(target = "id", source = "result.id")
    @Mapping(target = "createTime", source = "result.createTime")
    @Mapping(target = "fileId", source = "sourceFile.fileId")
    @Mapping(target = "fileName", source = "sourceFile.fileName")
    @Mapping(target = "fileSize", source = "sourceFile.fileSize")
    @Mapping(target = "storageType", ignore = true)
    @Mapping(target = "storageSourceId", ignore = true)
    @Mapping(target = "storageSourceName", ignore = true)
    @Mapping(target = "currentStorageSourceId", ignore = true)
    @Mapping(target = "currentStorageType", ignore = true)
    @Mapping(target = "currentStorageSourceName", ignore = true)
    @Mapping(target = "stageStatuses", ignore = true)
    FileResultVO toFileResultVO(WbFileResult result, WbSourceFile sourceFile);

    /**
     * 提交日志实体 + 解析任务 ID → 提交响应（无任务传 null）。
     *
     * @param log         提交日志实体
     * @param parseTaskId 解析任务 ID
     * @return 提交响应
     */
    default FileSubmitVO toSubmitResponse(WbSubmitLog log, Long parseTaskId) {
        return new FileSubmitVO(toSubmitLogVO(log), parseTaskId);
    }

    // ===== KnowledgeBaseConverter =====

    /**
     * 创建请求 → 知识库实体（仅字段搬运）。
     *
     * @param dto 创建请求
     * @return 知识库实体（服务端字段未填）
     */
    @InheritConfiguration(name = "serverFields")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "bindingProfileVersionId", ignore = true)
    @Mapping(target = "indexConfig", ignore = true)
    @Mapping(target = "defaultRuleId", ignore = true)
    @Mapping(target = "publishedIndexSetId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    WbKnowledgeBase toEntity(KnowledgeBaseCreateDTO dto);

    // ===== PreprocessVoConverter =====

    /**
     * 归一化字段 → 字段视图。
     *
     * @param field 归一化字段
     * @return 字段视图
     */
    PreprocessFieldVO toFieldVO(NormalizedField field);

    /**
     * 处理轨迹条目 → 轨迹视图。
     *
     * @param entry 轨迹条目
     * @return 轨迹视图
     */
    PreprocessTraceVO toTraceVO(TraceEntry entry);

    /**
     * 表格单元格 → 单元格视图。
     *
     * @param cell 视图单元格
     * @return 单元格视图
     */
    PreprocessCellVO toCellVO(ViewCell cell);

    /**
     * 视图元素 → 元素视图（字段明细随元素映射；轨迹由默认方法补齐）。
     *
     * @param element 视图元素
     * @return 元素视图
     */
    @Mapping(target = "fields", source = "normalizedFields")
    @Mapping(target = "trace", ignore = true)
    PreprocessElementVO mapElement(ViewElement element);

    /**
     * 视图元素转元素 VO：补齐非 KEEP 的处理轨迹。
     *
     * @param element 视图元素
     * @return 元素 VO
     */
    default PreprocessElementVO toElementVO(ViewElement element) {
        PreprocessElementVO vo = mapElement(element);
        if (NullUtil.isNotNull(element.getPreprocessTrace())) {
            vo.setTrace(PreprocessViewRules.nonKeepTrace(element).stream()
                    .map(this::toTraceVO)
                    .toList());
        }
        return vo;
    }

    /**
     * 视图元素转元素 VO 列表。
     *
     * @param view 预处理视图
     * @return 元素 VO 列表
     */
    default List<PreprocessElementVO> toElementVOs(PreprocessView view) {
        return ObjectUtil.defaultIfNull(view.getElements(), new ArrayList<ViewElement>()).stream()
                .map(this::toElementVO)
                .toList();
    }

    // ===== StructureVoConverter =====

    /**
     * 单条冲突记录 → 冲突视图。
     *
     * @param record 冲突记录
     * @return 冲突视图
     */
    StructureConflictVO toConflictVO(ConflictRecord record);

    /**
     * 冲突记录列表 → 冲突视图列表。
     *
     * @param records 冲突记录列表
     * @return 冲突视图列表
     */
    List<StructureConflictVO> toConflictVOs(List<ConflictRecord> records);

    /**
     * 大纲元素 → 大纲视图（单元格明细随元素映射）。
     *
     * @param element 统一文档元素
     * @return 大纲视图
     */
    @Mapping(target = "elementId", source = "id")
    StructureOutlineVO toOutlineVO(UnifiedElement element);

    /**
     * 单元格元素 → 单元格视图。
     *
     * @param cell 单元格元素
     * @return 单元格视图
     */
    StructureCellVO toCellVO(UnifiedElement cell);

    /**
     * 质量告警转展示文本（级别 + 码值 + 说明）。
     *
     * @param quality 文档质量
     * @return 告警文本列表；没有告警时为空列表
     */
    default List<String> toWarningTexts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getWarnings())) {
            return new ArrayList<>();
        }
        return quality.getWarnings().stream()
                .map(w -> w.getLevel() + " " + w.getCode() + ": " + w.getMessage())
                .toList();
    }

    /**
     * 冲突记录转冲突视图；没有冲突时为空列表。
     *
     * @param quality 文档质量
     * @return 冲突视图列表
     */
    default List<StructureConflictVO> toConflicts(DocumentQuality quality) {
        if (NullUtil.isNull(quality) || NullUtil.isNull(quality.getConflicts())) {
            return new ArrayList<>();
        }
        return toConflictVOs(quality.getConflicts());
    }

    /**
     * 文档内容大纲：元素按阅读顺序全量返回，跳文档根与单元格。
     *
     * @param document 统一文档产物
     * @return 大纲视图列表
     */
    default List<StructureOutlineVO> toOutline(UnifiedDocument document) {
        List<UnifiedElement> elements = ObjectUtil.defaultIfNull(document.getElements(), new ArrayList<>());
        return elements.stream()
                .filter(e -> !UnifiedElementType.DOCUMENT.name().equals(e.getType())
                        && !UnifiedElementType.TABLE_CELL.name().equals(e.getType()))
                .map(this::toOutlineVO)
                .toList();
    }


    // ===== 手写静态映射（落库行装配 / 环节条目骨架）=====

    default StageContentItemVO itemOf(int seq, String type, String status, String display,
                                            StageContentExtraVO extra) {
        StageContentItemVO item = new StageContentItemVO();
        item.setAlignKey(String.valueOf(seq));
        item.setSeq(seq);
        item.setType(type);
        item.setStatus(status);
        item.setDisplay(display);
        item.setExtra(extra);
        return item;
    }

    default void putPageGrid(StageContentExtraVO extra, Integer page, Integer rows, Integer cols) {
        if (NullUtil.isNotNull(page)) {
            extra.setPage(page);
        }
        if (NullUtil.isNotNull(rows)) {
            extra.setRows(rows);
        }
        if (NullUtil.isNotNull(cols)) {
            extra.setCols(cols);
        }
    }

    default void putBbox(StageContentExtraVO extra, BBox bbox) {
        if (NullUtil.isNull(bbox)) {
            return;
        }
        BBoxVO box = new BBoxVO();
        box.setX(bbox.getX());
        box.setY(bbox.getY());
        box.setWidth(bbox.getWidth());
        box.setHeight(bbox.getHeight());
        extra.setBbox(box);
    }

    default WbEmbeddingSet setRow(Long fileResultId, EmbeddingSet set, String strategyVersion,
                                        ObjectRef ref) {
        WbEmbeddingSet row = new WbEmbeddingSet();
        row.setFileResultId(fileResultId);
        row.setChunkSetRef(set.getChunkSetRef());
        row.setEmbeddingSetId(set.getEmbeddingSetId());
        row.setStrategyVersion(strategyVersion);
        row.setModel(set.getModel());
        row.setDimension(set.getDimension());
        row.setMetric(set.getMetric());
        row.setNormalized(set.isNormalized());
        row.setRecordCount(set.getRecordCount());
        row.setCachedCount(set.getCachedCount());
        row.setStatus(RowStatus.ACTIVE.name());
        row.setArtifactId(ref.objectKey());
        row.setStorageType(ref.storageType().getCode());
        row.setStorageSourceId(ref.sourceId());
        row.setBucket(ref.bucket());
        return row;
    }

    default WbEmbeddingRecord recordRow(Long embeddingSetId, EmbeddingRecord record) {
        WbEmbeddingRecord row = new WbEmbeddingRecord();
        row.setEmbeddingSetId(embeddingSetId);
        row.setEmbeddingId(record.getEmbeddingId());
        row.setChunkId(record.getChunkId());
        row.setContentType(record.getContentType());
        row.setParentChunkId(record.getParentChunkId());
        row.setInputText(record.getInputText());
        row.setInputTextHash(record.getInputTextHash());
        row.setTokenCount(record.getTokenCount());
        row.setRequestId(record.getRequestId());
        row.setStatus(record.getStatus());
        return row;
    }

    /**
     * 切片集合行：切片数与总字符由调用方算好传入。
     *
     * @param fileResultId      文件结果 ID
     * @param upstreamProductId 上游产物 ID
     * @param strategyVersion   切片策略完整版本
     * @param chunkCount        切片数
     * @param totalChars        总字符数
     * @param product           本环节产物行（提供产物引用列）
     * @return 切片集合行（未保存）
     */
    default WbChunkSet toChunkSetRow(Long fileResultId, Long upstreamProductId, String strategyVersion,
                                     Integer chunkCount, Integer totalChars, WbPipelineProduct product) {
        WbChunkSet row = new WbChunkSet();
        row.setFileResultId(fileResultId);
        row.setUpstreamProductId(upstreamProductId);
        row.setChunkStrategyVersion(strategyVersion);
        row.setChunkCount(chunkCount);
        row.setTotalChars(totalChars);
        row.setStatus(RowStatus.ACTIVE.name());
        row.setArtifactId(product.getArtifactId());
        row.setStorageType(product.getStorageType());
        row.setStorageSourceId(product.getStorageSourceId());
        row.setBucket(product.getBucket());
        return row;
    }

    /**
     * 切片行：页码范围由调用方格式化后传入（落库口径在调用方）。
     *
     * @param chunk           切片产物元素
     * @param chunkSetId      切片集合行 ID
     * @param strategyVersion 切片策略完整版本
     * @param pageRange       已格式化的页码范围
     * @return 切片行（未保存）
     */
    default WbChunk toChunkRow(Chunk chunk, Long chunkSetId, String strategyVersion, String pageRange) {
        WbChunk row = new WbChunk();
        row.setChunkSetId(chunkSetId);
        row.setChunkId(chunk.getChunkId());
        row.setParentChunkId(chunk.getParentChunkId());
        row.setContent(chunk.getContent());
        row.setContentType(chunk.getContentType());
        row.setTitlePath(chunk.getTitlePath());
        row.setPageRange(pageRange);
        row.setTableRef(chunk.getTableRef());
        row.setOrderNo(chunk.getOrder());
        row.setCharCount(chunk.getCharCount());
        row.setTokenCount(chunk.getTokenCount());
        row.setStrategyVersion(strategyVersion);
        return row;
    }

    /**
     * 流水线产物行：产物引用列取自对象存储位置。
     *
     * @param task               所属任务
     * @param stage              产出环节
     * @param upstreamProductId  上游产物 ID
     * @param capabilitySnapshot 能力/策略版本快照
     * @param ref                产物对象位置
     * @return 产物行（未保存）
     */
    default WbPipelineProduct toProduct(WbPipelineTask task, PipelineStage stage, Long upstreamProductId,
                                        String capabilitySnapshot, ObjectRef ref) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setTaskId(task.getId());
        product.setFileResultId(task.getFileResultId());
        product.setStage(stage.name());
        product.setUpstreamProductId(upstreamProductId);
        product.setCapabilitySnapshot(capabilitySnapshot);
        product.setArtifactId(ref.objectKey());
        product.setStorageType(ref.storageType().getCode());
        product.setStorageSourceId(ref.sourceId());
        product.setBucket(ref.bucket());
        product.setContentHash(ref.objectKey());
        return product;
    }

    /**
     * 步骤日志行：步骤字段由 {@link StepLogFields#copyFrom} 提供，计数字段与错误截断仍在调用方。
     *
     * @param taskId 任务 ID
     * @param step   步骤日志字段来源
     * @return 步骤日志行（未保存）
     */
    default WbPipelineStepLog toStepLog(Long taskId, StepLogFields step) {
        WbPipelineStepLog stepLog = new WbPipelineStepLog();
        stepLog.copyFrom(step);
        stepLog.setTaskId(taskId);
        return stepLog;
    }

    /**
     * 索引版本行：版本号由调用方取（含并发冲突重取），本方法只做字段搬运。
     *
     * @param set         索引集合
     * @param versionNo   版本号
     * @param comboJson   组合快照 JSON
     * @param chunkCount  切片数
     * @param vectorCount 向量数
     * @return 版本行（未保存）
     */
    default WbIndexVersion toIndexVersion(WbIndexSet set, String versionNo, String comboJson,
                                          Integer chunkCount, Integer vectorCount) {
        WbIndexVersion version = new WbIndexVersion();
        version.setIndexSetId(set.getId());
        version.setVersionNo(versionNo);
        version.setComboSnapshot(comboJson);
        version.setStatus(IndexVersionStatus.CREATED.name());
        version.setChunkCount(chunkCount);
        version.setVectorCount(vectorCount);
        return version;
    }
}
