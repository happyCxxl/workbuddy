package com.workbuddy.biz.service.support.index;

import com.workbuddy.common.dto.request.index.BuildOrderDTO;
import com.workbuddy.common.dto.request.index.ComboSnapshotDTO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.BuildOrder;
import com.workbuddy.worker.indexing.ComboSnapshot;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;

/**
 * 索引构建命令工厂：请求体 + 路径参数 → 算法层命令。
 *
 * <p>输出的是算法层对象（`worker.indexing`），不属于 data-carrier 转换层（`JAVA-DTO-013`），
 * 因此留在本模块手写，不进 `common.convert`。
 */
@Component
public class BuildOrderCommandFactory {

    /**
     * 请求体转构建命令：知识库 ID 取路径参数，其余字段原样搬运。
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

    /**
     * 组合快照 DTO → 算法层快照：DTO 侧维度为 null 时保留快照自身的默认值。
     *
     * @param dto 组合快照请求
     * @return 算法层快照
     */
    ComboSnapshot toSnapshot(ComboSnapshotDTO dto) {
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
