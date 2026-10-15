package com.workbuddy.common.dto.response.retrieval;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索响应：命中列表 + 执行口径（规则/版本/耗时/运行记录 ID）。
 *
 * @author cxxl
 */
@Data
public class SearchVO {

    /** 查询文本 */
    private String query;

    /** 规则 name-version（引擎基线为 baseline-v0） */
    private String ruleNameVersion;

    /** 索引版本号 */
    private String versionNo;

    /** 耗时（毫秒）。不是雪花 ID，保持数字类型即可 */
    private Long elapsedMs;

    /**
     * 运行记录 ID（测试台检索有值；生产检索按开关记录）。
     *
     * <p>**必须字符串化**：雪花 ID 有 19 位，超过 JS 的 Number.MAX_SAFE_INTEGER，
     * 作为裸数字下发会被前端 JSON.parse 抹掉末几位 —— 而前端要拿它去调对比接口，
     * 失真后会报"运行记录不存在"。项目里其它雪花 ID 字段同样处理。
     */
    private Long runId;

    /** 命中列表 */
    private List<SearchHitVO> hits = new ArrayList<>();
}
