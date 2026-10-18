package com.workbuddy.biz.service.support;

import com.workbuddy.common.dto.request.index.BuildOrderDTO;
import com.workbuddy.common.dto.request.index.ComboSnapshotDTO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 索引构建请求到构建命令的纯映射：知识库 ID 取路径参数，其余字段原样搬运。
 *
 * <p>契约对象与算法层的命令对象分开：请求体形状变化不牵动任务快照的兼容性。
 */
@Component
public class BuildOrderAssembler {

    /**
     * 请求体转构建命令。
     *
     * @param request         索引构建请求
     * @param knowledgeBaseId 路径参数里的知识库 ID
     * @return 构建命令；请求为空时返回 {@code null}
     */
    public BuildOrder toCommand(BuildOrderDTO request, Long knowledgeBaseId) {
        if (NullUtil.isNull(request)) {
            return null;
        }
        BuildOrder order = new BuildOrder();
        order.setKnowledgeBaseId(knowledgeBaseId);
        order.setTrigger(request.getTrigger());
        order.setComboSnapshot(toSnapshot(request.getComboSnapshot()));
        return order;
    }

    private ComboSnapshot toSnapshot(ComboSnapshotDTO dto) {
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
}
