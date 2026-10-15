package com.workbuddy.worker.structure.impl;
import com.workbuddy.common.enums.structure.UnifiedElementType;

import com.workbuddy.common.domain.structure.DocumentInfo;
import com.workbuddy.common.domain.structure.DocumentRelation;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.utils.JsonUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * UnifiedDocument JSON 序列化往返单测（hutool JsonUtil 口径）。
 *
 * @author cxxl
 */
class UnifiedDocumentJsonTest {

    @Test
    void roundTripShouldPreserveStructure() {
        UnifiedDocument document = createDocument();

        String json = JsonUtil.toJsonStr(document);
        UnifiedDocument restored = JsonUtil.toObject(json, UnifiedDocument.class);

        assertNotNull(restored);
        assertEquals("doc-1", restored.getDocumentInfo().getDocumentId());
        assertEquals("1.0.0", restored.getDocumentInfo().getSchemaVersion());
        assertEquals(1, restored.getElements().size());
        assertEquals(UnifiedElementType.TITLE.name(), restored.getElements().getFirst().getType());
        assertEquals(1, restored.getElements().getFirst().getLevel());
        assertEquals(1, restored.getRelations().size());
    }

    @NotNull
    private static UnifiedDocument createDocument() {
        UnifiedDocument document = new UnifiedDocument();
        DocumentInfo info = new DocumentInfo();
        info.setDocumentId("doc-1");
        info.setSourceFileRef("F-88");
        info.setSourceFileType("application/pdf");
        info.setParserRunId(1L);
        info.setSchemaVersion("1.0.0");
        document.setDocumentInfo(info);

        UnifiedElement title = new UnifiedElement();
        title.setId("n-abc");
        title.setType(UnifiedElementType.TITLE.name());
        title.setText("第一章 投标人须知");
        title.setLevel(1);
        document.setElements(List.of(title));

        document.setRelations(List.of(new DocumentRelation("NEXT", "n-abc", "n-def", null)));
        return document;
    }
}
