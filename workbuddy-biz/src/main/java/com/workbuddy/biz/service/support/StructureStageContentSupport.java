package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.domain.structure.UnifiedElement;
import com.workbuddy.common.dto.response.stagecontent.StageContentExtraVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装环节内容：统一文档元素按序映射 type/status/display，extra 带层级、页网格与边界框。
 */
public final class StructureStageContentSupport {

    private StructureStageContentSupport() {
    }

    /**
     * 统一文档产物 → 内容条目。
     *
     * @param fileStorage 对象存储
     * @param ref         组装产物对象位置
     * @return 内容条目；产物缺失或读不到时为空列表
     */
    public static List<StageContentItemVO> items(FileStorage fileStorage, ObjectRef ref) {
        UnifiedDocument document = StageArtifactReader.read(fileStorage, ref, UnifiedDocument.class);
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
    private static StageContentExtraVO structureExtra(UnifiedElement element) {
        StageContentExtraVO extra = new StageContentExtraVO();
        if (NullUtil.isNotNull(element.getLevel())) {
            extra.setLevel(element.getLevel());
        }
        StageContentItemSupport.putPageGrid(extra, element.getPage(), element.getRows(), element.getCols());
        StageContentItemSupport.putBbox(extra, element.getBbox());
        return extra;
    }
}
