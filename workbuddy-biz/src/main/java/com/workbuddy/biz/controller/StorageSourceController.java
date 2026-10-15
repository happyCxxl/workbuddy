package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.StorageSourceService;
import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 存储数据源 Controller：数据源清单、当前启用预览与设为当前启用，全部仅管理员可用。
 *
 * @author cxxl
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/system/storage-sources")
@Tag(name = "存储数据源", description = "存储数据源的清单与当前启用切换")
public class StorageSourceController {

    private final StorageSourceService storageSourceService;

    @GetMapping
    @AdminOnly
    @Operation(summary = "数据源列表", description = "全部数据源：名称、类型、状态、是否当前启用、连接参数（按键取值，含密钥类参数）、运行时接入标记与最近一次探测结论；不探活")
    public R<List<StorageSourceVO>> list() {
        return R.ok(storageSourceService.list());
    }

    @PostMapping("/{id}/current/preview")
    @AdminOnly
    @Operation(summary = "切换当前启用预览", description = "校验目标可用与参数齐全后统计影响面；不落库、不切换、不探活")
    public R<StorageSwitchPreviewVO> previewCurrent(@PathVariable Long id) {
        return R.ok(storageSourceService.previewCurrent(id));
    }

    @PostMapping("/{id}/current")
    @AdminOnly
    @Operation(summary = "设为当前启用", description = "连接测试通过后先清后置 is_current 并替换运行时的当前启用，回写这次探测结论，返回切换后的当前值与影响面")
    public R<StorageSwitchResultVO> switchCurrent(@PathVariable Long id) {
        log.info("===> StorageSourceController switchCurrent 设为当前启用, id={}", id);
        return R.ok(storageSourceService.switchCurrent(id, currentOperator()), "切换成功");
    }

    /** 当前登录用户名（管理员接口在线调用，登录态必有） */
    private String currentOperator() {
        KnowledgeUser user = SecurityUtil.getUser();
        return NullUtil.isNull(user) ? null : user.getUsername();
    }
}
