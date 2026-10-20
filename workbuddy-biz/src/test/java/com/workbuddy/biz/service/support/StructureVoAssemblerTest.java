package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.structure.ConflictRecord;
import com.workbuddy.common.domain.structure.DocumentQuality;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.enums.parse.QualityWarningCode;
import com.workbuddy.common.enums.structure.RelationType;
import com.workbuddy.common.enums.structure.UnifiedElementType;
import com.workbuddy.biz.service.support.assemble.StructureVoAssembler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装详情 VO 组装器单测（纯映射无状态类，用真实实例断言）。
 */
class StructureVoAssemblerTest {

    private final StructureVoAssembler assembler = new StructureVoAssembler();

    private UnifiedElement element(String id, String type) {
        UnifiedElement element = new UnifiedElement();
        element.setId(id);
        element.setType(type);
        element.setText("文本" + id);
        return element;
    }

    /**
     * 文档摘要：按类型与关系计数。
     *
     * <p>守护 {@code toSummary}：分类口径错即失败。
     */
    @Test
    void toSummaryShouldCountByTypeAndRelations() {
        UnifiedDocument document = new UnifiedDocument();
        document.getElements().add(element("h-1", UnifiedElementType.TITLE.name()));
        document.getElements().add(element("n-1", UnifiedElementType.PARAGRAPH.name()));
        document.getElements().add(element("n-2", UnifiedElementType.PARAGRAPH.name()));
        document.getElements().add(element("t-1", UnifiedElementType.TABLE.name()));
        document.getRelations().add(new DocumentRelation(RelationType.PARENT_CHILD.name(), "h-1", "n-1", null));
        DocumentQuality quality = new DocumentQuality();
        quality.getConflicts().add(new ConflictRecord());
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.NOISE_PAGE, null, "WARN", "告警"));
        document.setQuality(quality);

        var summary = assembler.toSummary(document);

        assertEquals(4, summary.getElementCount());
        assertEquals(1, summary.getTitleCount());
        assertEquals(2, summary.getParagraphCount());
        assertEquals(1, summary.getTableCount());
        assertEquals(0, summary.getImageCount());
        assertEquals(1, summary.getRelationCount());
        assertEquals(1, summary.getConflictCount());
        assertEquals(1, summary.getWarningCount());
    }

    /**
     * 大纲：跳过文档根与表格单元格。
     *
     * <p>守护 {@code toOutline}：根节点或单元格混进大纲即失败。
     */
    @Test
    void toOutlineShouldSkipDocumentRootAndTableCells() {
        UnifiedDocument document = new UnifiedDocument();
        document.getElements().add(element("doc", UnifiedElementType.DOCUMENT.name()));
        document.getElements().add(element("t-1", UnifiedElementType.TABLE.name()));
        UnifiedElement cell = element("c-1", UnifiedElementType.TABLE_CELL.name());
        document.getElements().add(cell);

        var outline = assembler.toOutline(document);

        assertEquals(1, outline.size());
        assertEquals("t-1", outline.getFirst().getElementId());
    }

    /**
     * 大纲：表格的嵌套单元格逐列映射。
     *
     * <p>守护 {@code toOutline}：单元格行列/文本/表头漏搬即失败。
     */
    @Test
    void toOutlineShouldMapNestedCells() {
        UnifiedDocument document = new UnifiedDocument();
        UnifiedElement table = element("t-1", UnifiedElementType.TABLE.name());
        table.setCells(new ArrayList<>());
        UnifiedElement cell = element("c-1", UnifiedElementType.TABLE_CELL.name());
        cell.setRow(0);
        cell.setCol(1);
        cell.setText("单元格文本");
        cell.setIsHeader(true);
        table.getCells().add(cell);
        document.getElements().add(table);

        var outline = assembler.toOutline(document);

        assertEquals(1, outline.getFirst().getCells().size());
        assertEquals(0, outline.getFirst().getCells().getFirst().getRow());
        assertEquals(1, outline.getFirst().getCells().getFirst().getCol());
        assertEquals("单元格文本", outline.getFirst().getCells().getFirst().getText());
        assertTrue(outline.getFirst().getCells().getFirst().getIsHeader());
    }

    /**
     * 大纲条目：层级、页码、页范围、行列数、表格标题与冲突标记逐列下发。
     *
     * <p>守护 {@code toOutlineVO}：漏搬任一列都会让结构详情的表格/标题卡片缺信息。
     */
    @Test
    void toOutlineShouldCarryAllColumns() {
        UnifiedDocument document = new UnifiedDocument();
        UnifiedElement table = element("t-9", UnifiedElementType.TABLE.name());
        table.setLevel(2);
        table.setPage(3);
        table.setPageRange(List.of(3, 4));
        table.setRows(5);
        table.setCols(6);
        table.setCaption("报价表");
        table.setConflictStatus("CONFLICT");
        document.getElements().add(table);

        var vo = assembler.toOutline(document).getFirst();

        assertEquals("t-9", vo.getElementId());
        assertEquals(UnifiedElementType.TABLE.name(), vo.getType());
        assertEquals(2, vo.getLevel());
        assertEquals(3, vo.getPage());
        assertEquals(List.of(3, 4), vo.getPageRange());
        assertEquals(5, vo.getRows());
        assertEquals(6, vo.getCols());
        assertEquals("报价表", vo.getCaption());
        assertEquals("CONFLICT", vo.getConflictStatus());
    }

    /**
     * 质量信息：冲突三列与告警文本按契约拼装。
     *
     * <p>守护 {@code toConflicts}：漏列或文案格式错即失败。
     */
    @Test
    void toConflictsAndWarningsShouldFormatFromQuality() {
        DocumentQuality quality = new DocumentQuality();
        ConflictRecord record = new ConflictRecord();
        record.setPrimaryElementId("p-1");
        record.setBackupElementId("b-1");
        record.setMessage("无法裁决");
        quality.getConflicts().add(record);
        quality.getWarnings().add(QualityWarning.of(QualityWarningCode.SCANNED_PAGE, null, "WARN", "第 2 页无文本层"));

        var conflicts = assembler.toConflicts(quality);
        var warnings = assembler.toWarningTexts(quality);

        assertEquals(1, conflicts.size());
        assertEquals("p-1", conflicts.getFirst().getPrimaryElementId());
        assertEquals("b-1", conflicts.getFirst().getBackupElementId());
        assertEquals("无法裁决", conflicts.getFirst().getMessage());
        assertEquals(List.of("WARN SCANNED_PAGE: 第 2 页无文本层"), warnings);
        // 质量信息缺失：两个列表都为空（不是 null）
        assertTrue(assembler.toConflicts(null).isEmpty());
        assertTrue(assembler.toWarningTexts(new DocumentQuality()).isEmpty());
    }
}
