package com.workbuddy.common.dto.request.retrieval;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 检索请求：
 * 生产检索不传 ruleId/versionId（走回退链）；测试台检索必须同时传（显式版本+规则）。
 * 过滤项为请求参数，不属于规则本体。
 *
 * @author cxxl
 */
@Data
public class SearchRequest {

    /** 查询文本 */
    @NotBlank(message = "query 不能为空")
    private String query;

    /** 返回条数（可覆盖规则 topK） */
    private Integer topK;

    /** 过滤：文件结果 ID */
    private Long documentId;

    /** 过滤：所有者 */
    private String owner;

    /** 过滤：片类型 */
    private String contentType;

    /** 显式规则行 ID（测试台；与 versionId 成对出现） */
    private Long ruleId;

    /** 显式索引版本行 ID（测试台；含候选冻结集） */
    private Long versionId;
}
