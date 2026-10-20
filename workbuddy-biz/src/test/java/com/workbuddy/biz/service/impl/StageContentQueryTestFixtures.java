package com.workbuddy.biz.service.impl;

import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.enums.task.PipelineTaskStatus;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.mockito.Mockito.when;

/**
 * 产物内容查询测试共享夹具：实体行、产物 JSON 与任务/产物打桩，避免各用例重复拼装。
 */
final class StageContentQueryTestFixtures {

    /** 被测文件结果 ID */
    static final Long FILE_RESULT_ID = 10L;

    /** 任务 ID */
    static final Long TASK_ID = 41L;

    /** 产物 ID */
    static final Long PRODUCT_ID = 50L;

    /** 产物所在的数据源 */
    static final Long SOURCE_ID = 1L;

    private StageContentQueryTestFixtures() {
    }

    /** 一次运行的文件结果（挂 10 号知识库，归属校验要能过） */
    static WbFileResult fileResultOfKb10() {
        WbFileResult fileResult = new WbFileResult();
        fileResult.setId(FILE_RESULT_ID);
        fileResult.setKnowledgeBaseId(10L);
        return fileResult;
    }

    /** 归属校验用的知识库行 */
    static WbKnowledgeBase knowledgeBaseOf(Long id) {
        WbKnowledgeBase kb = new WbKnowledgeBase();
        kb.setId(id);
        return kb;
    }

    /** 最新任务行（产物 ID 固定为 50） */
    static WbPipelineTask latestTask(String stage) {
        WbPipelineTask task = new WbPipelineTask();
        task.setId(TASK_ID);
        task.setFileResultId(FILE_RESULT_ID);
        task.setStage(stage);
        task.setStatus(PipelineTaskStatus.SUCCESS.name());
        task.setProductId(PRODUCT_ID);
        return task;
    }

    /** 产物行夹具：读取按记录里的数据源与桶名定位，行对象必须带上这两段 */
    static WbPipelineProduct productRow(String artifactId) {
        WbPipelineProduct product = new WbPipelineProduct();
        product.setArtifactId(artifactId);
        product.setStorageType(StorageType.MINIO.getCode());
        product.setStorageSourceId(SOURCE_ID);
        product.setBucket("artifacts");
        return product;
    }

    /**
     * 打桩产物内容：按产物对象位置写 JSON 字节。
     *
     * @param fileStorage 对象存储
     * @param artifactId  产物键
     * @param value       产物对象
     */
    static void stubArtifact(FileStorage fileStorage, String artifactId, Object value) {
        when(fileStorage.getObject(Objects.requireNonNull(
                ObjectRef.ofProduct(productRow(artifactId)))))
                .thenReturn(Objects.requireNonNull(JsonUtil.toJsonStr(value)).getBytes(StandardCharsets.UTF_8));
    }

    /** 解析产物夹具：每页一个元素，坐标随页号变化 */
    static ParseResult parseArtifactWithBbox(int elementCount) {
        List<ParseElement> elements = new ArrayList<>();
        for (int i = 1; i <= elementCount; i++) {
            ParseElement element = ParseElement.of("el-" + i, ElementType.PARAGRAPH);
            element.setPage(i);
            element.setBbox(new BBox(72, 100 * i, 400, 20));
            elements.add(element);
        }
        return parseResult(elements);
    }

    /** 解析产物夹具：每页一个元素，无坐标 */
    static ParseResult parseArtifact(int elementCount) {
        List<ParseElement> elements = new ArrayList<>();
        for (int i = 1; i <= elementCount; i++) {
            ParseElement element = ParseElement.of("el-" + i, ElementType.PARAGRAPH);
            element.setPage(i);
            elements.add(element);
        }
        return parseResult(elements);
    }

    /** 解析产物夹具：一个 2×2 表格，格内有文本 */
    static ParseResult parseArtifactWithTable() {
        ParseElement table = ParseElement.of("t1_0_0", ElementType.TABLE);
        table.setPage(1);
        table.setRows(2);
        table.setCols(2);
        List<ParseElement> cells = new ArrayList<>();
        String[] texts = {"Contract No.", "ABC-1234", "Amount", "1,234,567"};
        for (int index = 0; index < texts.length; index++) {
            ParseElement cell = ParseElement.of("t1_0_0_" + index, ElementType.TABLE_CELL);
            cell.setText(texts[index]);
            cell.setRow(index / 2);
            cell.setCol(index % 2);
            cells.add(cell);
        }
        table.setCells(cells);
        return parseResult(List.of(table));
    }

    /** 构造解析产物（sources 平铺元素） */
    static ParseResult parseResult(List<ParseElement> elements) {
        ParseSource source = new ParseSource();
        source.setSource("native");
        source.setProvider("test");
        source.setElements(elements);
        ParseResult result = new ParseResult();
        result.setSources(List.of(source));
        return result;
    }

    /** 结构化产物夹具 */
    static UnifiedDocument structureDocument(List<UnifiedElement> elements) {
        UnifiedDocument document = new UnifiedDocument();
        document.setElements(elements);
        return document;
    }

    /** 结构化元素夹具 */
    static UnifiedElement element(String id, String type, String text) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setText(text);
        return element;
    }

    /** 预处理产物夹具 */
    static PreprocessView preprocessView(List<ViewElement> elements) {
        PreprocessView view = new PreprocessView();
        view.setElements(elements);
        return view;
    }
}
