package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.common.web.R;
import com.workbuddy.biz.service.RetrievalRuleService;
import com.workbuddy.biz.service.SearchService;
import com.workbuddy.common.dto.request.retrieval.RetrievalRulePublishDTO;
import com.workbuddy.common.dto.request.retrieval.RetrievalRunCompareDTO;
import com.workbuddy.common.dto.request.retrieval.SearchDTO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRulePublishVO;
import com.workbuddy.common.dto.response.retrieval.RetrievalRunVO;
import com.workbuddy.common.dto.response.retrieval.SearchVO;
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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-base")
public class KnowledgeRetrievalController {

    private final SearchService searchService;

    private final RetrievalRuleService retrievalRuleService;

    /**
     * 检索：按知识库默认规则执行一次查询并记录运行。
     *
     * @param id      知识库 ID
     * @param request 检索请求
     * @return 检索结果
     */
    @PostMapping("/{id}/search")
    public R<SearchVO> search(
            @PathVariable("id") Long id,
            @Valid @RequestBody SearchDTO request) {
        return R.ok(searchService.search(id, request));
    }

    /**
     * 检索测试：与正式检索同一口径，供参数调试。
     *
     * @param id      知识库 ID
     * @param request 检索请求
     * @return 检索结果
     */
    @PostMapping("/{id}/retrieval-test")
    public R<SearchVO> retrievalTest(
            @PathVariable("id") Long id,
            @Valid @RequestBody SearchDTO request) {
        return R.ok(searchService.search(id, request));
    }

    /**
     * 最近的检索运行记录。
     *
     * @param id    知识库 ID
     * @param limit 条数上限
     * @return 运行记录列表
     */
    @GetMapping("/{id}/retrieval-runs")
    public R<List<RetrievalRunVO>> runs(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "50") int limit) {
        return R.ok(searchService.listRuns(id, limit));
    }

    /**
     * 对比多次检索运行的结果。
     *
     * @param id  知识库 ID
     * @param dto 对比请求（运行记录 ID 列表）
     * @return 各次运行的结果
     */
    @PostMapping("/{id}/retrieval-runs/compare")
    public R<List<SearchVO>> compare(
            @PathVariable("id") Long id,
            @Valid @RequestBody RetrievalRunCompareDTO dto) {
        return R.ok(searchService.compareRuns(id, dto.getRunIds()));
    }

    /**
     * 发布知识库的默认检索规则。
     *
     * @param id  知识库 ID
     * @param dto 规则发布请求
     * @return 发布结果
     */
    @PostMapping("/{id}/retrieval-rules/publish")
    public R<RetrievalRulePublishVO> publishRule(
            @PathVariable("id") Long id,
            @Valid @RequestBody RetrievalRulePublishDTO dto) {
        return R.ok(retrievalRuleService.publishDefaultRule(id, dto));
    }
}
