package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.StatsSupport;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产物内容查询实现：
 * 白名单五环节；按 task.productId 精确取该次运行的产物内容（与详情接口同口径，历史任务同样可展示）；
 * 对齐键：PARSE/STRUCTURE/PREPROCESS=elementId、CHUNK/EMBED=顺序号。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StageContentQueryServiceImpl implements StageContentQueryService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbChunkDbService chunkDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final WbEmbeddingRecordDbService embeddingRecordDbService;
    private final FileStorage fileStorage;
    private final TaskDetailSupport detailSupport;
    private final FileResultAccessGuard accessGuard;

    /** 不带状态与切片过滤的重载：与两个过滤参数都传空是同一口径。 */
    public StageContentVO stageContent(Long fileResultId, String stage, Long taskId, Long docPage,
                                       Integer page, Integer limit) {
        return stageContent(fileResultId, stage, taskId, docPage, page, limit, null, null);
    }

    /**
     * 产物内容查询：按环节取该次运行产物的内容条目（分页 + 状态/文档页/切片过滤）。
     *
     * <p>存储类读取失败（40454 / 40455）向上抛出；其余读取失败记日志并留空条目，不阻断详情。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节（FILE_CHAIN_STAGES 白名单）
     * @param taskId       任务 ID（可空，缺省取该环节最新任务）
     * @param docPage      文档页过滤（可空）
     * @param page         页码（可空，缺省第 1 页）
     * @param limit        每页条数（可空，缺省上限）
     * @param status       处置状态过滤（可空）
     * @param chunkFilter  切片内容过滤（可空）
     * @return 产物内容分页
     */
    @Override
    public StageContentVO stageContent(Long fileResultId, String stage, Long taskId, Long docPage,
                                       Integer page, Integer limit, String status, ChunkContentFilterDTO chunkFilter) {
        accessGuard.requireExisting(fileResultId);
        ThrowUtil.throwIf(StrUtil.isBlank(stage) || !PipelineStage.FILE_CHAIN_STAGES.contains(stage),
                ErrorCode.PARAM_INVALID, "未知环节: " + stage);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, stageOf(stage), taskId, stageLabel(stage));

        int pageNo = resolvePage(page);
        int pageSize = resolveLimit(limit);
        StageContentVO vo = new StageContentVO();
        vo.setFileResultId(fileResultId);
        vo.setStage(stage);
        vo.setLatest(false);
        vo.setDocPage(NullUtil.isNull(docPage) || docPage < 1 ? null : docPage.intValue());
        vo.setItems(new ArrayList<>());
        vo.setTotal(0);
        vo.setPage(pageNo);
        vo.setLimit(pageSize);
        vo.setTruncated(false);
        if (NullUtil.isNull(task)) {
            return vo;
        }
        vo.setTaskId(task.getId());
        // latest = 该次运行产物内容是否可用：按 task.productId 精确取产物（历史任务同样可展示自己的内容）
        WbPipelineProduct product = NullUtil.isNull(task) || NullUtil.isNull(task.getProductId()) ? null
                : pipelineProductDbService.getById(task.getProductId());
        if (NullUtil.isNull(product) || StrUtil.isBlank(product.getArtifactId())) {
            return vo;
        }
        vo.setLatest(true);

        try {
            if (PipelineStage.CHUNK.name().equals(stage)) {
                // 切片：过滤与分页都下推到 wb_knowledge_chunk（切片集合可能很大），total 按过滤后的口径给
                fillChunkPage(vo, ObjectRef.ofProduct(product), chunkFilter, pageNo, pageSize);
            } else {
                // 页码与状态过滤都先于分页：total 与翻页都按过滤后的口径给
                List<StageContentItemVO> all = filterByStatus(
                        filterByDocPage(buildItems(stage, product), docPage), status);
                vo.setTotal(all.size());
                vo.setItems(slice(all, pageNo, pageSize));
            }
        } catch (Exception e) {
            KnowledgeException storageFailure = StatsSupport.storageFailureOf(e);
            if (NullUtil.isNotNull(storageFailure)) {
                throw storageFailure;
            }
            log.warn("产物内容读取失败, fileResultId={}, stage={}, artifactId={}",
                    fileResultId, stage, product.getArtifactId(), e);
            vo.setItems(new ArrayList<>());
            vo.setTotal(0);
        }
        // 截断 = 这一页之后还有内容（含页码越界）
        vo.setTruncated(hasMore(vo.getPage(), vo.getLimit(), vo.getItems().size(), vo.getTotal()));
        return vo;
    }

    /** 页码归一：非正数或空按第 1 页 */
    private int resolvePage(Integer page) {
        return NullUtil.isNull(page) || page < 1 ? 1 : page;
    }

    /** 每页条数归一：空/非正按默认上限，超过上限按上限截断 */
    private int resolveLimit(Integer limit) {
        if (NullUtil.isNull(limit) || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 这一页之后是否还有内容。
     *
     * <p>**不能写成 `page × limit < total`**：最后一页不满时（5 条按每页 2 条取第 3 页，
     * 只回 1 条）`3 × 2 = 6` 不小于 5，会把"已到末页"报成还有内容。按"这一页取到的位移
     * 是否到达总数"判断才等价于"后面还有没有"。
     *
     * @param page      当前页码
     * @param limit     本页条数上限
     * @param pageCount 本页实际返回条数
     * @param total     总条数
     * @return 还有内容未返回则为 true（页码越界为空页时同样为 true）
     */
    private boolean hasMore(int page, int limit, int pageCount, int total) {
        if (total <= 0) {
            return false;
        }
        return (page - 1) * limit + pageCount < total;
    }

    /** 取某一页：越界返回空列表（total 与 truncated 由调用处据实标明） */
    private List<StageContentItemVO> slice(List<StageContentItemVO> all, int page, int limit) {
        int from = (page - 1) * limit;
        if (from >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + limit, all.size())));
    }

    private List<StageContentItemVO> buildItems(String stage, WbPipelineProduct product) {
        ObjectRef ref = ObjectRef.ofProduct(product);
        return switch (stage) {
            case "PARSE" -> parseItems(ref);
            case "STRUCTURE" -> structureItems(ref);
            case "PREPROCESS" -> preprocessItems(ref);
            case "EMBED" -> embedItemsBySet(embeddingSetDbService.getByArtifactId(ref.objectKey()));
            default -> List.of();
        };
    }

    // ---------------- 解析：sources 平铺元素 ----------------

    private List<StageContentItemVO> parseItems(ObjectRef ref) {
        ParseResult result = readArtifact(ref, ParseResult.class);
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(result) || NullUtil.isNull(result.getSources())) {
            return items;
        }
        int seq = 1;
        for (ParseSource source : result.getSources()) {
            for (ParseElement element : ObjectUtil.defaultIfNull(source.getElements(), List.<ParseElement>of())) {
                StageContentItemVO item = new StageContentItemVO();
                item.setAlignKey(element.getId());
                item.setSeq(seq++);
                item.setType(element.getType());
                item.setDisplay(element.getText());
                item.setExtra(parseExtra(source, element));
                items.add(item);
            }
        }
        return items;
    }

    /** 解析环节专属字段 + 公共页网格字段 + 原文定位锚点 + 表格单元格明细 */
    private Map<String, Object> parseExtra(ParseSource source, ParseElement element) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("source", source.getSource());
        extra.put("provider", source.getProvider());
        putPageGrid(extra, element.getPage(), element.getRows(), element.getCols());
        putBbox(extra, element.getBbox());
        putOfficeAnchor(extra, element);
        // 表格单元格明细（TABLE 元素内嵌）：解析详情按行列渲染网格，单元格与文本原样下发、不做截断
        if (NullUtil.isNotNull(element.getCells()) && !element.getCells().isEmpty()) {
            extra.put("cells", JsonUtil.toJsonStr(element.getCells()));
        }
        return extra;
    }

    /**
     * Office 原文定位锚点：原文预览按这些字段把元素对到渲染结果上。
     *
     * <p>Word 段落给样式名（渲染器把样式名写在段落 DOM 上）；Excel 给工作表名与单元格行列
     * （行列是解析产物里的 0 基下标，与表格区域高亮同一口径）。
     */
    private void putOfficeAnchor(Map<String, Object> extra, ParseElement element) {
        if (StrUtil.isNotBlank(element.getStyle())) {
            extra.put("style", element.getStyle());
        }
        if (StrUtil.isNotBlank(element.getSheetName())) {
            extra.put("sheetName", element.getSheetName());
        }
        if (NullUtil.isNotNull(element.getRow())) {
            extra.put("row", element.getRow());
        }
        if (NullUtil.isNotNull(element.getCol())) {
            extra.put("col", element.getCol());
        }
        if (NullUtil.isNotNull(element.getRowSpan())) {
            extra.put("rowSpan", element.getRowSpan());
        }
        if (NullUtil.isNotNull(element.getColSpan())) {
            extra.put("colSpan", element.getColSpan());
        }
        if (NullUtil.isNotNull(element.getIsHeader())) {
            extra.put("isHeader", element.getIsHeader());
        }
    }

    // ---------------- 组装：章节树元素 ----------------

    private List<StageContentItemVO> structureItems(ObjectRef ref) {
        UnifiedDocument document = readArtifact(ref, UnifiedDocument.class);
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(document) || NullUtil.isNull(document.getElements())) {
            return items;
        }
        int seq = 1;
        for (UnifiedElement element : document.getElements()) {
            StageContentItemVO item = new StageContentItemVO();
            item.setAlignKey(element.getId());
            item.setSeq(seq++);
            item.setType(element.getType());
            item.setStatus(element.getConflictStatus());
            item.setDisplay(element.getText());
            item.setExtra(structureExtra(element));
            items.add(item);
        }
        return items;
    }

    /** 组装环节专属字段 + 公共页网格字段 */
    private Map<String, Object> structureExtra(UnifiedElement element) {
        Map<String, Object> extra = new LinkedHashMap<>();
        if (NullUtil.isNotNull(element.getLevel())) {
            extra.put("level", element.getLevel());
        }
        putPageGrid(extra, element.getPage(), element.getRows(), element.getCols());
        putBbox(extra, element.getBbox());
        return extra;
    }

    /** 页/行/列公共字段按非空并入 extra（解析/组装共用口径） */
    private void putPageGrid(Map<String, Object> extra, Integer page, Integer rows, Integer cols) {
        if (NullUtil.isNotNull(page)) {
            extra.put("page", page);
        }
        if (NullUtil.isNotNull(rows)) {
            extra.put("rows", rows);
        }
        if (NullUtil.isNotNull(cols)) {
            extra.put("cols", cols);
        }
    }

    /**
     * 边界框并入 extra：原文预览按它在页面上画高亮框。
     *
     * <p>单位点（pt）、左上角原点，与解析产物里的 bbox 同口径；元素无坐标时不下发该字段。
     */
    private void putBbox(Map<String, Object> extra, BBox bbox) {
        if (NullUtil.isNull(bbox)) {
            return;
        }
        Map<String, Object> box = new LinkedHashMap<>();
        box.put("x", bbox.getX());
        box.put("y", bbox.getY());
        box.put("width", bbox.getWidth());
        box.put("height", bbox.getHeight());
        extra.put("bbox", box);
    }

    /** 元素的文档页码：解析/组装走 extra.page，其余环节无页概念返回 null */
    private Long docPageOf(StageContentItemVO item) {
        Object value = NullUtil.isNull(item.getExtra()) ? null : item.getExtra().get("page");
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 按文档页过滤：只留该页元素（无页概念/非该页的一律排除）。
     *
     * <p>比较用 Long：入参来自 HTTP 是大整数，而元素页码在产物里是 Integer，
     * 直接比会因类型不同永远不相等。
     */
    private List<StageContentItemVO> filterByDocPage(List<StageContentItemVO> all, Long docPage) {
        if (NullUtil.isNull(docPage) || docPage < 1) {
            return all;
        }
        return all.stream()
                .filter(item -> ObjectUtil.equal(docPageOf(item), docPage))
                .toList();
    }

    /**
     * 按处置状态过滤：只留状态在给定集合里的元素。
     *
     * <p>状态以逗号分隔（如 `EXCLUDED_TOC,REPEATED`）；空串或全是空白表示不过滤。
     * 与文档页过滤同一原则：先过滤再分页，total 与 truncated 都按过滤后的口径给。
     */
    private List<StageContentItemVO> filterByStatus(List<StageContentItemVO> all, String status) {
        if (StrUtil.isBlank(status)) {
            return all;
        }
        Set<String> wanted = Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return all;
        }
        return all.stream()
                .filter(item -> wanted.contains(item.getStatus()))
                .toList();
    }

    // ---------------- 预处理：视图元素（display + normalized + 状态） ----------------

    private List<StageContentItemVO> preprocessItems(ObjectRef ref) {
        PreprocessView view = readArtifact(ref, PreprocessView.class);
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(view) || NullUtil.isNull(view.getElements())) {
            return items;
        }
        int seq = 1;
        for (ViewElement element : view.getElements()) {
            StageContentItemVO item = new StageContentItemVO();
            item.setAlignKey(element.getElementId());
            item.setSeq(seq++);
            item.setType(element.getType());
            item.setStatus(element.getStatus());
            item.setDisplay(element.getDisplayText());
            item.setNormalized(element.getNormalizedText());
            Map<String, Object> extra = new LinkedHashMap<>();
            if (StrUtil.isNotBlank(element.getRawText())) {
                extra.put("rawText", element.getRawText());
            }
            if (NullUtil.isNotNull(element.getMarks()) && !element.getMarks().isEmpty()) {
                extra.put("marks", element.getMarks());
            }
            if (NullUtil.isNotNull(element.getNormalizedFields()) && !element.getNormalizedFields().isEmpty()) {
                extra.put("normalizedFields", JsonUtil.toJsonStr(element.getNormalizedFields()));
            }
            if (NullUtil.isNotNull(element.getCells()) && !element.getCells().isEmpty()) {
                extra.put("cells", JsonUtil.toJsonStr(element.getCells()));
            }
            if (NullUtil.isNotNull(element.getPage())) {
                extra.put("page", element.getPage());
            }
            // 处理轨迹只对被处理过的元素下发（非 KEEP 条目），避免响应体膨胀
            List<TraceEntry> trace = nonKeepTrace(element);
            if (!trace.isEmpty()) {
                extra.put("trace", JsonUtil.toJsonStr(trace));
            }
            item.setExtra(extra);
            items.add(item);
        }
        return items;
    }

    /** 处理轨迹里的非 KEEP 条目（KEEP = 未命中改写，下发没有意义） */
    private List<TraceEntry> nonKeepTrace(ViewElement element) {
        return ObjectUtil.defaultIfNull(element.getPreprocessTrace(), List.<TraceEntry>of()).stream()
                .filter(entry -> !TraceEntry.ACTION_KEEP.equals(entry.getAction()))
                .toList();
    }

    // ---------------- 切片：wb_knowledge_chunk 内容（按该次运行产物 artifactId 精确取集合） ----------------

    /**
     * 切片分页内容：过滤（内容类型 / 是否兜底 / 有无父片）与分页都下推 DB，条目按集合内顺序给出对齐键。
     *
     * <p>total 取过滤后的总条数，与 items 同一口径（先过滤再分页）。
     */
    private void fillChunkPage(StageContentVO vo, ObjectRef ref, ChunkContentFilterDTO filter,
                               int pageNo, int pageSize) {
        WbChunkSet chunkSet = chunkSetDbService.getByArtifactId(ref.objectKey());
        if (NullUtil.isNull(chunkSet)) {
            return;
        }
        IPage<WbChunk> chunkPage = chunkDbService.pageByChunkSetId(chunkSet.getId(), filter, pageNo, pageSize);
        List<StageContentItemVO> items = new ArrayList<>();
        for (WbChunk chunk : chunkPage.getRecords()) {
            items.add(chunkItemOf(chunk));
        }
        vo.setTotal((int) chunkPage.getTotal());
        vo.setItems(items);
    }

    /** 切片条目：对齐键取切片 ID（与详情侧的片同键，两侧互相定位） */
    private StageContentItemVO chunkItemOf(WbChunk chunk) {
        StageContentItemVO item = itemOf(0, chunk.getContentType(), null, chunk.getContent(),
                chunkExtra(chunk));
        item.setSeq(ObjectUtil.defaultIfNull(chunk.getOrderNo(), 0));
        item.setAlignKey(StrUtil.blankToDefault(chunk.getChunkId(), String.valueOf(item.getSeq())));
        return item;
    }

    // ---------------- 向量化：记录列表（按该次运行产物 artifactId 精确取集合） ----------------

    private List<StageContentItemVO> embedItemsBySet(WbEmbeddingSet embeddingSet) {
        List<StageContentItemVO> items = new ArrayList<>();
        if (NullUtil.isNull(embeddingSet)) {
            return items;
        }
        int seq = 1;
        for (WbEmbeddingRecord record : embeddingRecordDbService.listByEmbeddingSetId(embeddingSet.getId())) {
            items.add(itemOf(seq, record.getContentType(), record.getStatus(), record.getInputText(),
                    embedExtra(record)));
            seq++;
        }
        return items;
    }

    /** 切片/向量化共用条目组装：对齐键取顺序号 */
    private StageContentItemVO itemOf(int seq, String type, String status, String display,
                                      Map<String, Object> extra) {
        StageContentItemVO item = new StageContentItemVO();
        item.setAlignKey(String.valueOf(seq));
        item.setSeq(seq);
        item.setType(type);
        item.setStatus(status);
        item.setDisplay(display);
        item.setExtra(extra);
        return item;
    }

    /** 切片环节专属字段（来源元素个数与兜底原因只在切片产物里，不进这里） */
    private Map<String, Object> chunkExtra(WbChunk chunk) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("chunkId", chunk.getChunkId());
        extra.put("contentType", chunk.getContentType());
        extra.put("titlePath", chunk.getTitlePath());
        extra.put("charCount", chunk.getCharCount());
        extra.put("tokenCount", chunk.getTokenCount());
        extra.put("orderNo", chunk.getOrderNo());
        extra.put("parentChunkId", chunk.getParentChunkId());
        extra.put("pageRange", chunk.getPageRange());
        extra.put("tableRef", chunk.getTableRef());
        return extra;
    }

    /** 向量化环节专属字段 */
    private Map<String, Object> embedExtra(WbEmbeddingRecord record) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("chunkId", record.getChunkId());
        extra.put("tokenCount", record.getTokenCount());
        extra.put("cacheHit", record.getCacheHit());
        extra.put("requestId", record.getRequestId());
        return extra;
    }

    /**
     * 读产物本体：对象位置取自产物行（存储类型 + 桶名 + sha256）。
     *
     * @param ref   产物对象位置
     * @param clazz 产物本体类型
     * @return 产物本体；读不到返回 null
     */
    private <T> T readArtifact(ObjectRef ref, Class<T> clazz) {
        byte[] content = fileStorage.getObject(ref);
        if (NullUtil.isNull(content)) {
            return null;
        }
        return JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), clazz);
    }

    private PipelineStage stageOf(String stage) {
        return PipelineStage.valueOf(stage);
    }

    /** 环节中文标签：可识别的环节取枚举标签，未知环节回落原值 */
    private String stageLabel(String stage) {
        try {
            return stageOf(stage).getLabel();
        } catch (IllegalArgumentException e) {
            return stage;
        }
    }
}
