package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.IndexSetService;
import com.workbuddy.biz.service.support.BuildOrderAssembler;
import com.workbuddy.common.dto.request.index.BuildOrderDTO;
import com.workbuddy.common.dto.response.index.IndexBuildTriggerVO;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.dto.response.index.IndexValidateVO;
import com.workbuddy.common.dto.response.index.IndexVersionVO;
import com.workbuddy.common.web.R;
import jakarta.validation.Valid;
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
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-base")
public class KnowledgeFileIndexController {

    private final IndexSetService indexSetService;

    private final BuildOrderAssembler buildOrderAssembler;

    /**
     * 列出知识库的索引版本。
     *
     * @param id 知识库 ID
     * @return 索引版本列表
     */
    @GetMapping("/{id}/index-sets")
    public R<List<IndexVersionVO>> list(
            @PathVariable("id") Long id) {
        return R.ok(indexSetService.listVersions(id));
    }

    /**
     * 列出可构建的索引组合。
     *
     * @param id            知识库 ID
     * @param fileResultIds 限定参与的文件结果 ID（可空）
     * @return 组合列表
     */
    @GetMapping("/{id}/index-combos")
    public R<List<IndexComboVO>> combos(
            @PathVariable("id") Long id,
            @RequestParam(value = "fileResultIds", required = false) List<Long> fileResultIds) {
        return R.ok(indexSetService.listCombos(id, fileResultIds));
    }

    /**
     * 触发候选索引构建：登记组合并投递 BUILD_INDEX 任务。
     *
     * @param id      知识库 ID
     * @param request 构建请求
     * @return 任务 ID 与生效策略
     */
    @PostMapping("/{id}/index-sets")
    public R<IndexBuildTriggerVO> build(
            @PathVariable("id") Long id,
            @Valid @RequestBody BuildOrderDTO request) {
        log.info("===> KnowledgeFileIndexController build 触发索引构建, kbId={}, trigger={}",
                id, request.getTrigger());
        return R.ok(indexSetService.buildCandidate(buildOrderAssembler.toCommand(request, id)), "构建任务已入队");
    }

    /**
     * 索引版本详情。
     *
     * @param id        知识库 ID
     * @param versionId 版本 ID
     * @return 版本详情
     */
    @GetMapping("/{id}/index-sets/{versionId}")
    public R<IndexVersionVO> detail(
            @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        return R.ok(indexSetService.versionDetail(id, versionId));
    }

    /**
     * 校验索引候选：对账组合范围与向量集合是否一致。
     *
     * @param id        知识库 ID
     * @param versionId 版本 ID
     * @return 校验结果
     */
    @PostMapping("/{id}/index-sets/{versionId}/validate")
    public R<IndexValidateVO> validate(
            @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        return R.ok(indexSetService.validate(versionId));
    }

    /**
     * 发布索引版本（原子切换线上版本）。
     *
     * @param id        知识库 ID
     * @param versionId 版本 ID
     * @return 恒为 true
     */
    @PostMapping("/{id}/index-sets/{versionId}/publish")
    public R<Boolean> publish(
            @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController publish 发布索引, kbId={}, versionId={}", id, versionId);
        indexSetService.publish(versionId);
        return R.ok(true, "发布成功");
    }

    /**
     * 回退到该索引版本。
     *
     * @param id        知识库 ID
     * @param versionId 版本 ID
     * @return 恒为 true
     */
    @PostMapping("/{id}/index-sets/{versionId}/rollback")
    public R<Boolean> rollback(
            @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController rollback 回退索引, kbId={}, versionId={}", id, versionId);
        indexSetService.rollback(versionId);
        return R.ok(true, "回退成功");
    }

    /**
     * 回收索引候选（删除版本与其向量集合）。
     *
     * @param id        知识库 ID
     * @param versionId 版本 ID
     * @return 恒为 true
     */
    @DeleteMapping("/{id}/index-sets/{versionId}")
    public R<Boolean> delete(
            @PathVariable("id") Long id,
            @PathVariable("versionId") Long versionId) {
        log.info("===> KnowledgeFileIndexController delete 回收索引候选, kbId={}, versionId={}", id, versionId);
        indexSetService.recycle(versionId);
        return R.ok(true, "删除成功");
    }
}
