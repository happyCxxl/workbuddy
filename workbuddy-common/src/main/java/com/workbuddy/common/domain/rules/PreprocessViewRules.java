package com.workbuddy.common.domain.rules;

import com.workbuddy.common.enums.preprocess.ViewElementStatus;

import java.util.List;
import java.util.stream.Stream;

/**
 * 预处理视图规则：派生视图的展示统计口径（biz/worker 可引用）。
 *
 * @author cxxl
 */
public final class PreprocessViewRules {

    /** 剔除态（不进 normalizedText 内容流；展示视图完整保留） */
    public static final List<String> EXCLUDED_STATUSES = List.of(
            ViewElementStatus.EXCLUDED_HEADER.name(),
            ViewElementStatus.EXCLUDED_FOOTER.name(),
            ViewElementStatus.EXCLUDED_TOC.name(),
            ViewElementStatus.EXCLUDED_NOISE.name(),
            ViewElementStatus.BACKUP_SKIPPED.name());

    /** 切片环节跳过态：剔除态 + 重复份（均不进检索内容流，不参与切片） */
    public static final List<String> CHUNK_SKIP_STATUSES = Stream.concat(
            EXCLUDED_STATUSES.stream(), Stream.of(ViewElementStatus.REPEATED.name())).toList();

    private PreprocessViewRules() {
    }
}
