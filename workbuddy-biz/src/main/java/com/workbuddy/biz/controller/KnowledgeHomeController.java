package com.workbuddy.biz.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.biz.service.KnowledgeHomeService;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.request.home.HomeRecentSubmitQueryDto;
import com.workbuddy.common.dto.response.home.HomeRecentSubmitVO;
import com.workbuddy.common.dto.response.home.HomeSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页 Controller：资产速览 + 最近提交。
 *
 * <p>独立于各业务 Controller：首页只读、跨模块聚合。数据来源是各资产表计数与
 * `wb_submit_log`（最近提交）。
 *
 * <p>**最近提交只返回当前登录用户的记录**：接口不接受"提交人"参数，提交人由服务端
 * 从安全上下文取。
 *
 * <p>不含环节分布、待处理清单与审计动作（那些在各业务页与管理视角）。
 *
 * <p>查询对象收参（{@link HomeRecentSubmitQueryDto}）：DTO 字段按同名的独立查询参数绑定。
 *
 * <p>分页条件不加 `@Valid`：DTO 没有约束注解，取值由 `currentOrDefault` / `sizeOrDefault` 兜底。
 *
 * @author cxxl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/home")
public class KnowledgeHomeController {

    private final KnowledgeHomeService knowledgeHomeService;

    @GetMapping("/summary")
    public R<HomeSummaryVO> summary() {
        return R.ok(knowledgeHomeService.summary());
    }

    @GetMapping("/recent-submits")
    public R<IPage<HomeRecentSubmitVO>> recentSubmits(HomeRecentSubmitQueryDto query) {
        return R.ok(knowledgeHomeService.recentSubmits(query));
    }
}
