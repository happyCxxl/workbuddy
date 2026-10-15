package com.workbuddy.common.domain.structure;

import com.workbuddy.common.domain.parse.QualityWarning;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一文档质量：告警 + 冲突（只标记不阻断）。
 *
 * @author cxxl
 */
@Data
public class DocumentQuality {

    /** 告警列表 */
    private List<QualityWarning> warnings = new ArrayList<>();

    /** 冲突列表（双路并存：PRIMARY/BACKUP） */
    private List<ConflictRecord> conflicts = new ArrayList<>();
}
