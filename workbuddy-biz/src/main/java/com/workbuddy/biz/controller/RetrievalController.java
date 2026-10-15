package com.workbuddy.biz.controller;

import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.RetrievalRuleService;
import com.workbuddy.biz.service.SearchService;
import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDto;
import com.workbuddy.common.dto.request.retrieval.RetrievalRunCompareDto;
import com.workbuddy.common.dto.request.retrieval.SearchRequest;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRunVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索与评测 Controller：
 * 生产检索（回退链）/ 测试台检索（显式版本+规则，落运行记录）/ 勾选对比回放 / 规则选优发布。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-base")
@Tag(name = "检索与评测", description = "检索 / 测试台对比与选优发布")
public class RetrievalController {

    private final SearchService searchService;

    private final RetrievalRuleService retrievalRuleService;

    @PostMapping("/{id}/search")
    @Operation(summary = "生产检索", description = "不传 ruleId/versionId 走回退链（在线版本行 → kb 默认 → 引擎基线）；未发布 40446")
    public R<SearchVO> search(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody SearchRequest request) {
        return R.ok(searchService.search(id, request));
    }

    @PostMapping("/{id}/retrieval-test")
    @Operation(summary = "测试台检索", description = "必须显式 versionId+ruleId（可检索候选冻结集）；执行即落运行记录")
    public R<SearchVO> retrievalTest(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody SearchRequest request) {
        return R.ok(searchService.search(id, request));
    }

    @GetMapping("/{id}/retrieval-runs")
    @Operation(summary = "检索运行记录列表", description = "新→旧；limit 截断（≤0 = 全部）")
    public R<List<RetrievalRunVO>> runs(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Parameter(description = "返回条数上限") @RequestParam(defaultValue = "50") int limit) {
        return R.ok(searchService.listRuns(id, limit));
    }

    @PostMapping("/{id}/retrieval-runs/compare")
    @Operation(summary = "勾选对比回放", description = "按运行记录回放执行时刻快照并排（不重跑——快照即证据）")
    public R<List<SearchVO>> compare(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody RetrievalRunCompareDto dto) {
        return R.ok(searchService.compareRuns(id, dto.getRunIds()));
    }

    @PostMapping("/{id}/retrieval-rules/publish")
    @Operation(summary = "检索规则选优发布", description = "把规则行设为指定索引版本行的默认规则 + 审计（索引发布与规则发布为两个独立动作）")
    public R<RetrievalRulePublishVO> publishRule(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Valid @RequestBody RetrievalRulePublishDto dto) {
        return R.ok(retrievalRuleService.publishDefaultRule(id, dto));
    }
}
