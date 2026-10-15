package com.workbuddy.biz.controller;

import com.workbuddy.common.dto.response.R;
import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.worker.indexing.BuildOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 索引构建与发布 Controller：
 * 候选构建 → 验证 → 原子发布 → 回退 → 回收，挂 /knowledge-base/{id}。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-base")
@Tag(name = "索引构建与发布", description = "索引候选构建/验证/发布/回退/回收")
public class KnowledgeFileIndexController {

    private final IndexSetService indexSetService;

    @GetMapping("/{id}/index-sets")
    @Operation(summary = "索引版本列表", description = "新→旧，含在线发布标记（online=二级指针命中）")
    public R<List<IndexVersionVO>> list(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id) {
        return R.ok(indexSetService.listVersions(id));
    }

    @GetMapping("/{id}/index-combos")
    @Operation(summary = "可构建组合枚举清单", description = "测评模式构建弹窗数据源：组合+产物完整标记+文件数×向量数预览；"
            + "传 fileResultIds 收窄为冻结集口径（只返回范围内文件产物完整的组合）")
    public R<List<IndexComboVO>> combos(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @Parameter(description = "文件结果 ID 列表（冻结集枚举口径，可空）")
            @RequestParam(value = "fileResultIds", required = false) List<Long> fileResultIds) {
        return R.ok(indexSetService.listCombos(id, fileResultIds));
    }

    @PostMapping("/{id}/index-sets")
    @Operation(summary = "构建候选索引", description = "body=BuildOrder（文件范围×切片策略×向量策略×形态）；"
            + "产物不完整 40444、同组合活跃版本 40443")
    public R<IndexBuildTriggerVO> build(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @RequestBody BuildOrder order) {
        order.setKnowledgeBaseId(id);
        log.info("===> KnowledgeFileIndexController build 触发索引构建, kbId={}, trigger={}", id, order.getTrigger());
        return R.ok(indexSetService.buildCandidate(order), "构建任务已入队");
    }

    @GetMapping("/{id}/index-sets/{versionId}")
    @Operation(summary = "索引版本详情", description = "组合快照/统计/状态/任务/验证结果；不存在 40441")
    public R<IndexVersionVO> detail(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        return R.ok(indexSetService.versionDetail(id, versionId));
    }

    @PostMapping("/{id}/index-sets/{versionId}/validate")
    @Operation(summary = "验证索引版本", description = "一致性自检+抽样检索冒烟（向量1次+全文1次）；"
            + "仅 READY/ONLINE/RETIRED 可验证（其余 40443）")
    public R<IndexValidateVO> validate(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        return R.ok(indexSetService.validate(versionId));
    }

    @PostMapping("/{id}/index-sets/{versionId}/publish")
    @Operation(summary = "发布索引版本", description = "单事务三级指针原子切换，旧在线版自动退役；仅 READY 可发布")
    public R<Boolean> publish(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController publish 发布索引, kbId={}, versionId={}", id, versionId);
        indexSetService.publish(versionId);
        return R.ok(true, "发布成功");
    }

    @PostMapping("/{id}/index-sets/{versionId}/rollback")
    @Operation(summary = "回退索引版本", description = "指针切回目标版本（同事务）+ 自动补齐（差异文件重跑/无差异直接补构建）")
    public R<Boolean> rollback(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController rollback 回退索引, kbId={}, versionId={}", id, versionId);
        indexSetService.rollback(versionId);
        return R.ok(true, "回退成功");
    }

    @DeleteMapping("/{id}/index-sets/{versionId}")
    @Operation(summary = "回收索引候选", description = "逻辑删除候选（Milvus 回收+删行）；在线版 40442 禁删、构建中 40443")
    public R<Boolean> delete(
            @Parameter(description = "知识库ID", required = true) @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController delete 回收索引候选, kbId={}, versionId={}", id, versionId);
        indexSetService.recycle(versionId);
        return R.ok(true, "删除成功");
    }
}
