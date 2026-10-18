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

    @Test
    void toOutlineShouldMapNestedCells() {
        UnifiedDocument document = new UnifiedDocument();
        UnifiedElement table = element("t-1", UnifiedElementType.TABLE.name());
        table.setCells(new ArrayList<>());
        UnifiedElement cell = element("c-1", UnifiedElementType.TABLE_CELL.name());
        cell.setRow(0);
        cell.setCol(1);
        cell.setIsHeader(true);
        table.getCells().add(cell);
        document.getElements().add(table);

        var outline = assembler.toOutline(document);

        assertEquals(1, outline.getFirst().getCells().size());
        assertEquals(0, outline.getFirst().getCells().getFirst().getRow());
        assertEquals(1, outline.getFirst().getCells().getFirst().getCol());
        assertTrue(outline.getFirst().getCells().getFirst().getIsHeader());
    }

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
        assertEquals(List.of("WARN SCANNED_PAGE: 第 2 页无文本层"), warnings);
    }
}
