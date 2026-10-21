package com.workbuddy.biz.service.support.assemble;

import com.workbuddy.common.dto.request.index.BuildOrderDTO;
import com.workbuddy.common.dto.request.index.ComboSnapshotDTO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.mapstruct.Mapper;

import java.util.LinkedHashMap;

/**
 * 索引构建请求到构建命令的映射：知识库 ID 取路径参数，其余字段原样搬运（编译期生成实现）。
 *
 * <p>契约对象与算法层命令对象分开：请求体形状变化不牵动任务快照的兼容性。
 */
@Mapper(componentModel = "spring")
public interface BuildOrderAssembler {

    /**
     * 请求体转构建命令（不含知识库 ID）。
     *
     * @param request 索引构建请求
     * @return 构建命令；请求为空时返回 {@code null}
     */
    BuildOrder toCommand(BuildOrderDTO request);

    /**
     * 组合快照 DTO → 算法层快照：DTO 侧维度为 null 时保留快照自身的默认值（默认范围与索引形态）。
     *
     * @param dto 组合快照请求
     * @return 算法层快照
     */
    default ComboSnapshot toSnapshot(ComboSnapshotDTO dto) {
        if (NullUtil.isNull(dto)) {
            return null;
        }
        ComboSnapshot combo = new ComboSnapshot();
        if (NullUtil.isNotNull(dto.getFileScopeMode())) {
            combo.setFileScopeMode(dto.getFileScopeMode());
        }
        if (NullUtil.isNotNull(dto.getShape())) {
            combo.setShape(dto.getShape());
        }
        combo.setFileResultIds(dto.getFileResultIds());
        if (NullUtil.isNotNull(dto.getStageStrategies())) {
            combo.setStageStrategies(new LinkedHashMap<>(dto.getStageStrategies()));
        }
        return combo;
    }

    /**
     * 请求体 + 路径参数 → 构建命令：知识库 ID 取路径参数，其余字段原样搬运。
     *
     * @param request         索引构建请求
     * @param knowledgeBaseId 路径参数里的知识库 ID
     * @return 构建命令；请求为空时返回 {@code null}
     */
    default BuildOrder toCommand(BuildOrderDTO request, Long knowledgeBaseId) {
        if (NullUtil.isNull(request)) {
            return null;
        }
        BuildOrder order = toCommand(request);
        order.setKnowledgeBaseId(knowledgeBaseId);
        return order;
    }
}
