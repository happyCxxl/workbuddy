package com.workbuddy.biz.service.support;

import com.workbuddy.common.dto.request.index.BuildOrderDTO;
import com.workbuddy.common.dto.request.index.ComboSnapshotDTO;
import com.workbuddy.common.enums.index.IndexShape;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.biz.service.support.assemble.BuildOrderAssembler;
import com.workbuddy.biz.service.support.assemble.BuildOrderAssemblerImpl;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引构建请求装配器单测。
 *
 * <p>守护 {@code toCommand}/{@code toSnapshot} 的字段搬运、拷贝语义与空值兜底：
 * 知识库 ID 取路径参数，组合快照各维度原样搬运，请求为空返回 null。
 */
class BuildOrderAssemblerTest {

    private final BuildOrderAssembler assembler = new BuildOrderAssemblerImpl();

    /**
     * 请求为空返回 null。
     *
     * <p>守护 {@code toCommand} 的空请求兜底：去掉该判定即触发空指针。
     */
    @Test
    void toCommandShouldReturnNullForNullRequest() {
        assertNull(assembler.toCommand(null, 9L));
    }

    /**
     * 正常搬运：知识库 ID 取路径参数，trigger 与组合快照各字段原样搬运。
     *
     * <p>守护 {@code toCommand} 漏搬任一字段即失败。
     */
    @Test
    void toCommandShouldCarryAllFields() {
        BuildOrderDTO request = new BuildOrderDTO();
        request.setTrigger("manual");
        ComboSnapshotDTO snapshot = new ComboSnapshotDTO();
        snapshot.setFileScopeMode("LIST");
        snapshot.setShape(IndexShape.FULL_VECTOR);
        snapshot.setFileResultIds(List.of(1L, 2L));
        snapshot.setStageStrategies(new LinkedHashMap<>(Map.of(
                "PREPROCESS", "preproc-default-v1",
                "CHUNK", "chunk-hybrid-v1",
                "EMBED", "embed-default-v1")));
        request.setComboSnapshot(snapshot);

        BuildOrder order = assembler.toCommand(request, 9L);

        assertEquals(9L, order.getKnowledgeBaseId());
        assertEquals("manual", order.getTrigger());
        ComboSnapshot combo = order.getComboSnapshot();
        assertEquals("LIST", combo.getFileScopeMode());
        assertEquals(IndexShape.FULL_VECTOR, combo.getShape());
        assertEquals(List.of(1L, 2L), combo.getFileResultIds());
        assertEquals(3, combo.getStageStrategies().size());
        assertEquals("chunk-hybrid-v1", combo.getStageStrategies().get("CHUNK"));
    }

    /**
     * 环节策略映射按值拷贝：改请求体不再影响已生成的命令快照。
     *
     * <p>守护 {@code toSnapshot} 的拷贝语义：直接引用请求体的 map 即失败。
     */
    @Test
    void toCommandShouldCopyStageStrategies() {
        Map<String, String> strategies = new LinkedHashMap<>();
        strategies.put("CHUNK", "chunk-hybrid-v1");
        ComboSnapshotDTO snapshot = new ComboSnapshotDTO();
        snapshot.setStageStrategies(strategies);
        BuildOrderDTO request = new BuildOrderDTO();
        request.setComboSnapshot(snapshot);

        ComboSnapshot combo = assembler.toCommand(request, 9L).getComboSnapshot();
        strategies.put("CHUNK", "chunk-mutated");

        assertNotSame(strategies, combo.getStageStrategies());
        assertEquals("chunk-hybrid-v1", combo.getStageStrategies().get("CHUNK"));
    }

    /**
     * 组合快照为空时命令的组合也为 null。
     *
     * <p>守护 {@code toSnapshot} 的空值兜底。
     */
    @Test
    void toCommandShouldAllowNullSnapshot() {
        BuildOrderDTO request = new BuildOrderDTO();
        request.setTrigger("manual");

        BuildOrder order = assembler.toCommand(request, 9L);

        assertNull(order.getComboSnapshot());
    }

    /**
     * 快照维度显式为 null 时保留命令侧默认值，不把 null 覆盖进去。
     *
     * <p>守护 {@code toSnapshot} 各维度的空值判定：无脑覆盖会让默认范围与索引形态变成 null。
     */
    @Test
    void toCommandShouldKeepDefaultsForNullDimensions() {
        ComboSnapshotDTO snapshot = new ComboSnapshotDTO();
        snapshot.setFileScopeMode(null);
        snapshot.setShape(null);
        snapshot.setFileResultIds(null);
        snapshot.setStageStrategies(null);
        BuildOrderDTO request = new BuildOrderDTO();
        request.setComboSnapshot(snapshot);

        ComboSnapshot combo = assembler.toCommand(request, 9L).getComboSnapshot();

        assertEquals("ALL", combo.getFileScopeMode());
        assertEquals(IndexShape.FULL_VECTOR, combo.getShape());
        assertNull(combo.getFileResultIds());
        assertTrue(combo.getStageStrategies().isEmpty());
    }
}
