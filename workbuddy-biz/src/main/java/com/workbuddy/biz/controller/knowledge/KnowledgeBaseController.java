package com.workbuddy.biz.controller.knowledge;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.KnowledgeBaseService;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.request.input.FileSubmitDTO;
import com.workbuddy.common.dto.request.knowledge.FileResultQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseCreateDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseQueryDTO;
import com.workbuddy.common.dto.request.knowledge.KnowledgeBaseUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.StrategyBindingsUpdateDTO;
import com.workbuddy.common.dto.request.knowledge.SubmitLogQueryDTO;
import com.workbuddy.common.dto.response.input.FileResultVO;
import com.workbuddy.common.dto.response.input.FileSubmitVO;
import com.workbuddy.common.dto.response.input.SubmitLogVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseStatsVO;
import com.workbuddy.common.dto.response.knowledge.KnowledgeBaseVO;
import com.workbuddy.common.dto.response.knowledge.StrategyBindingVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库管理接口：知识库读写 + 文件提交（建档）、提交记录、文件结果查询
 * （三者归属与生命周期都由知识库决定，路径挂在 `/knowledge-base/{id}/**` 下）。
 * 业务编排在 `KnowledgeBaseService` 实现（含文档提交建档、提交记录与文件结果查询），本类只做薄转发。
 *
 * <p>**可见范围**：普通用户只读写自己创建的知识库，管理员不限归属
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/knowledge-base")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * 创建知识库：默认启用状态、归属为当前登录用户，同事务记 CREATE 审计。
     *
     * <p>无认证上下文直接抛 40101，不落 user_id 为空的孤儿行 —— 可见范围就是按 user_id 划分的，
     * 没有归属的库普通用户谁也看不见，只有管理员能碰。
     *
     * @param dto 创建请求体：name（必填，≤14 字符）、description（≤64 字符）、
     *            strategyBindingEnabled（1 开启 / 0 关闭；null 视为开启）
     * @return 新知识库 ID（雪花 ID 以字符串返回，避免前端 JS 大整数精度丢失）
     */
    @PostMapping
    public R<String> create(@Valid @RequestBody KnowledgeBaseCreateDTO dto) {
        log.info("===> KnowledgeBaseController create 创建知识库, name={}", dto.getName());
        return R.ok(String.valueOf(knowledgeBaseService.create(dto)), "创建成功");
    }

    /**
     * 更新知识库：名称、业务场景说明与策略绑定开关，同事务记 UPDATE 审计（before/after 快照）。
     *
     * <p>路径 id 覆盖请求体 id（`dto.setId(id)`），归属校验只认路径参数；只能改本人可访问的库，
     * 不可访问按「不存在」处理（40401）。开关口径：null 或非 0 一律归一为开启（1），兼容存量数据。
     *
     * @param id  知识库 ID（路径参数）
     * @param dto 更新请求体：name（必填，≤14 字符）、description（≤64 字符）、
     *            strategyBindingEnabled（可空，null 视为开启）
     * @return 是否更新成功（恒 true，失败以业务异常表达）
     */
    @PutMapping("/{id}")
    public R<Boolean> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody KnowledgeBaseUpdateDTO dto) {
        dto.setId(id);
        log.info("===> KnowledgeBaseController update 更新知识库, id={}", id);
        return R.ok(knowledgeBaseService.update(dto), "更新成功");
    }

    /**
     * 知识库详情：基础字段 + 预处理/切片/向量化策略绑定摘要 + 文档数（wb_file_result 计数）。
     *
     * <p>已逻辑删除不可见；非本人可访问的库按「不存在」处理（40401），不返回 40104
     * —— 后者等于确认该库存在，会把别人的库 ID 变成可探测的信息。
     *
     * @param id 知识库 ID（路径参数）
     * @return 知识库详情 VO
     */
    @GetMapping("/{id}")
    public R<KnowledgeBaseVO> detail(
            @PathVariable("id") Long id) {
        return R.ok(knowledgeBaseService.detail(id));
    }

    /**
     * 分页查询知识库：名称模糊 + 状态过滤 + 排序；每行回填策略绑定摘要、文档数与已发布索引版本号
     * （按页批量查询，不逐行查）。
     *
     * <p>可见范围在服务端定：普通用户只查自己创建的库，管理员不限；接口**不接受"创建人"参数**。
     * 未知 sort 值回落 DEFAULT（不报错，避免旧客户端失败）。
     *
     * @param query 查询条件（名称 / 状态 / 排序都可留空；current/size 缺省 1/10，小于 1 回落缺省）
     * @return 知识库分页 VO（不含已删除）
     */
    @GetMapping("/page")
    public R<IPage<KnowledgeBaseVO>> page(@Valid KnowledgeBaseQueryDTO query) {
        return R.ok(knowledgeBaseService.page(query));
    }

    /**
     * 统计概览：知识库总数、启用数、文档总数，仅统计未删除数据。
     *
     * <p>三个数与分页列表**同一可见范围**：文档数先取当前用户可见的库 ID 再按集合计数
     * （wb_file_result 没有归属列，不能直接按用户过滤）。
     *
     * @return 统计概览 VO
     */
    @GetMapping("/stats")
    public R<KnowledgeBaseStatsVO> stats() {
        return R.ok(knowledgeBaseService.stats());
    }

    /**
     * 停用知识库：停用后拒绝新文件接入与检索，同事务记 DISABLE 审计（before/after 快照）。
     *
     * <p>状态校验在服务端做：仅启用状态可停用（反之为 40402）；
     * 非本人可访问的库按「不存在」处理（40401）。
     *
     * @param id 知识库 ID（路径参数）
     * @return 是否停用成功（恒 true，失败以业务异常表达）
     */
    @PostMapping("/{id}/disable")
    public R<Boolean> disable(
            @PathVariable("id") Long id) {
        log.info("===> KnowledgeBaseController disable 停用知识库, id={}", id);
        return R.ok(knowledgeBaseService.disable(id), "停用成功");
    }

    /**
     * 启用知识库：仅停用状态可启用，同事务记 ENABLE 审计（before/after 快照）。
     *
     * @param id 知识库 ID（路径参数）
     * @return 是否启用成功（恒 true，失败以业务异常表达）
     */
    @PostMapping("/{id}/enable")
    public R<Boolean> enable(
            @PathVariable("id") Long id) {
        log.info("===> KnowledgeBaseController enable 启用知识库, id={}", id);
        return R.ok(knowledgeBaseService.enable(id), "启用成功");
    }

    /**
     * 删除知识库：逻辑删除（del_flag='1'），无恢复接口，同事务记 DELETE 审计。
     *
     * <p>非本人可访问的库按「不存在」处理（40401）。
     * 删除只作用于知识库本体，历史提交记录与文件结果不做级联清理。
     *
     * @param id 知识库 ID（路径参数）
     * @return 是否删除成功（恒 true，失败以业务异常表达）
     */
    @DeleteMapping("/{id}")
    public R<Boolean> delete(
            @PathVariable("id") Long id) {
        log.info("===> KnowledgeBaseController delete 删除知识库, id={}", id);
        return R.ok(knowledgeBaseService.delete(id), "删除成功");
    }


    /**
     * 提交文件：对文件引用做归属校验与文件校验后完成建档三写
     * （wb_source_file / wb_file_result / wb_submit_log，同一事务）；
     * 本接口只建档，不创建处理任务——解析由页面在文件结果页手动触发（手动逐环节口径）。
     *
     * <p>幂等语义：以 requestId 为幂等键；同键重复请求返回首次提交记录，
     * 不重复建档（并发冲突时回滚本事务并回放已有记录）。
     *
     * @param id      知识库 ID（路径参数）
     * @param request 提交请求体：fileId + requestId（幂等键）
     * @return 提交响应：新建提交 pipelineTaskId 为 null；幂等回放时回填该文件结果已有的解析任务 ID（可能为 null）
     */
    @PostMapping("/{id}/submit")
    public R<FileSubmitVO> submit(
            @PathVariable("id") Long id,
            @Valid @RequestBody FileSubmitDTO request) {
        log.info("===> KnowledgeBaseController submit 提交文件, kbId={}, fileId={}, requestId={}",
                id, request.getFileId(), request.getRequestId());
        return R.ok(knowledgeBaseService.submit(id, request));
    }

    /**
     * 提交记录分页查询：按知识库分页返回提交流水（PASS/FAIL 均记录，数据源 wb_submit_log），
     * 支持按提交结果与文件名模糊筛选；失败原因随记录一并返回。
     *
     * @param id    知识库 ID（路径参数）
     * @param query 查询条件（状态 / 文件名都可留空；current/size 缺省 1/10，小于 1 回落缺省）
     * @return 提交记录分页 VO
     */
    @GetMapping("/{id}/submit-logs")
    public R<IPage<SubmitLogVO>> submitLogs(
            @PathVariable("id") Long id,
            @Valid SubmitLogQueryDTO query) {
        return R.ok(knowledgeBaseService.pageSubmitLogs(id, query));
    }

    /**
     * 文件结果分页查询（执行链工作台列表数据源）：按知识库分页返回文件处理结果，
     * 每行附带各环节最新任务状态（PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED 五环节）。
     *
     * @param id    知识库 ID（路径参数）
     * @param query 查询条件（环节可留空；current/size 缺省 1/10，小于 1 回落缺省）
     * @return 文件结果分页 VO（含来源文件信息与各环节状态列表）
     */
    @GetMapping("/{id}/file-results")
    public R<IPage<FileResultVO>> fileResults(
            @PathVariable("id") Long id,
            @Valid FileResultQueryDTO query) {
        return R.ok(knowledgeBaseService.pageFileResults(id, query));
    }

    /**
     * 查询知识库某类型的策略绑定（单类型，页面回显用）。
     *
     * <p>未绑定、或绑定行存在但版本已被删/停用时，只返回 strategyType（不返回半截数据）；
     * 策略类型须在 BINDABLE_TYPES 白名单内（PREPROCESS/CHUNK/EMBED；白名单外 40001）；
     * 非本人可访问的库按「不存在」处理（40401）。
     *
     * @param id           知识库 ID（路径参数）
     * @param strategyType 策略类型（BINDABLE_TYPES 白名单：PREPROCESS/CHUNK/EMBED）
     * @return 策略绑定 VO（未绑定或版本失效时仅含 strategyType）
     */
    @GetMapping("/{id}/strategy-binding")
    public R<StrategyBindingVO> strategyBinding(
            @PathVariable("id") Long id,
            @RequestParam("strategyType") String strategyType) {
        return R.ok(knowledgeBaseService.strategyBinding(id, strategyType));
    }

    /**
     * 批量查询某策略类型下所有已绑定的知识库：一条 SQL 取回该类型全部绑定行（每条含
     * knowledgeBaseId），替代前端按库逐个查。
     *
     * <p>路径上没有知识库 ID，故**不按当前用户归属过滤**，返回的是该策略类型的全量绑定；
     * 版本被删或已停用的绑定行跳过（与单类型查询口径一致），无有效绑定时返回空列表。
     *
     * @param strategyType 策略类型（BINDABLE_TYPES 白名单：PREPROCESS/CHUNK/EMBED；白名单外 40001）
     * @return 每条含 knowledgeBaseId / strategyVersionId / 策略名与版本号；无绑定时空列表
     */
    @GetMapping("/strategy-bindings")
    public R<List<StrategyBindingVO>> strategyBindings(
            @RequestParam("strategyType") String strategyType) {
        return R.ok(knowledgeBaseService.strategyBindings(strategyType));
    }

    /**
     * 绑定/解绑知识库某类型的策略（单类型）：strategyVersionId 为 null = 解绑。
     *
     * <p>绑定前校验：知识库归属 + 绑定开关（关闭即评测模式，禁止绑定）+ 类型白名单
     * （PREPROCESS/CHUNK/EMBED）+ 版本存在 + 版本类型匹配 + 版本启用中，任一不满足 40001。
     * 解绑为绑定行逻辑删除（无绑定行时幂等成功）；重绑复用原行（含已删行），不占用唯一键；
     * 两种操作都记 BIND 审计。
     *
     * @param id  知识库 ID（路径参数）
     * @param dto 绑定请求体：strategyType（必填）+ strategyVersionId（null = 解绑）
     * @return 是否绑定成功（恒 true，失败以业务异常表达）
     */
    @PutMapping("/{id}/strategy-binding")
    public R<Boolean> bindStrategy(
            @PathVariable("id") Long id,
            @Valid @RequestBody StrategyBindingUpdateDTO dto) {
        log.info("===> KnowledgeBaseController bindStrategy 绑定知识库策略, id={}, type={}", id, dto.getStrategyType());
        return R.ok(knowledgeBaseService.bindStrategy(id, dto), "绑定成功");
    }

    /**
     * 批量设置知识库策略集合（发布口径：一次调用设置整套绑定）。
     *
     * <p>与单类型接口的差别：请求必须一次给全 BINDABLE_TYPES（PREPROCESS/CHUNK/EMBED）、
     * 每项都要有非空 strategyVersionId、且请求内类型不可重复（不满足即 40001）——
     * 「开关开着但还没绑」的中间态由不调用本接口来表达，一旦调用就必须给全。
     * 逐类型复用单类型绑定逻辑（校验/upsert/审计），外层事务覆盖，任一项失败整体回滚。
     *
     * @param id      知识库 ID（路径参数）
     * @param request 批量绑定请求体：bindings 列表（至少一项、类型不重复、版本非空）
     * @return 是否绑定成功（恒 true，失败以业务异常表达）
     */
    @PutMapping("/{id}/strategy-bindings")
    public R<Boolean> bindStrategies(
            @PathVariable("id") Long id,
            @Valid @RequestBody StrategyBindingsUpdateDTO request) {
        log.info("===> KnowledgeBaseController bindStrategies 批量绑定知识库策略集合, id={}", id);
        return R.ok(knowledgeBaseService.bindStrategies(id, request), "绑定成功");
    }
}
