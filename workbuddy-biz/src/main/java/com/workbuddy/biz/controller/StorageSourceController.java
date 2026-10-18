package com.workbuddy.biz.controller;

import com.workbuddy.biz.service.StorageSourceService;
import com.workbuddy.common.annotation.AdminOnly;
import com.workbuddy.common.web.R;
import com.workbuddy.common.dto.response.setting.StorageSourceVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchPreviewVO;
import com.workbuddy.common.dto.response.setting.StorageSwitchResultVO;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
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
public class StorageSourceController {

    private final StorageSourceService storageSourceService;

    @GetMapping
    @AdminOnly
    public R<List<StorageSourceVO>> list() {
        return R.ok(storageSourceService.list());
    }

    @PostMapping("/{id}/current/preview")
    @AdminOnly
    public R<StorageSwitchPreviewVO> previewCurrent(@PathVariable Long id) {
        return R.ok(storageSourceService.previewCurrent(id));
    }

    @PostMapping("/{id}/current")
    @AdminOnly
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
