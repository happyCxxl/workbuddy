package com.workbuddy.common.enums.structure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 统一元素类型单测。
 *
 * <p>守护类型词汇表的完整性：元素类型随结构化产物与视图落库，是解析→结构→
 * 预处理→切片全链路的传输词汇；缺一类即该类元素无处安放。
 */
class UnifiedElementTypeTest {

    /**
     * 结构树骨架类型齐备（文档→页→节→标题→正文单元）。
     *
     * <p>守护 {@code UnifiedElementType} 的骨架词汇：删掉任一即失败（结构组装无类型可用）。
     */
    @Test
    void skeletonTypesShouldExist() {
        assertNotNull(UnifiedElementType.DOCUMENT);
        assertNotNull(UnifiedElementType.PAGE);
        assertNotNull(UnifiedElementType.SECTION);
        assertNotNull(UnifiedElementType.TITLE);
        assertNotNull(UnifiedElementType.PARAGRAPH);
        assertNotNull(UnifiedElementType.LIST);
    }

    /**
     * 表格三级类型齐备（表→行→格），切片按行取格。
     *
     * <p>守护 {@code UnifiedElementType} 的表格词汇：缺 TABLE_CELL 即失败（单元格文本无处挂）。
     */
    @Test
    void tableHierarchyTypesShouldExist() {
        assertNotNull(UnifiedElementType.TABLE);
        assertNotNull(UnifiedElementType.TABLE_ROW);
        assertNotNull(UnifiedElementType.TABLE_CELL);
    }

    /**
     * 图片、图注、页眉页脚与公式类型齐备。
     *
     * <p>守护 {@code UnifiedElementType} 的版面词汇：缺任一即失败（对应版面元素被丢弃或误归类）。
     */
    @Test
    void layoutTypesShouldExist() {
        assertNotNull(UnifiedElementType.IMAGE);
        assertNotNull(UnifiedElementType.FIGURE_CAPTION);
        assertNotNull(UnifiedElementType.HEADER);
        assertNotNull(UnifiedElementType.FOOTER);
        assertNotNull(UnifiedElementType.EQUATION);
    }
}
