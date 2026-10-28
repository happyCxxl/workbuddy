package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.mapper.WbAuditLogMapper;
import com.workbuddy.biz.service.db.WbAuditLogDbService;
import com.workbuddy.common.domain.entity.WbAuditLog;
import com.workbuddy.common.enums.knowledge.AuditActionType;
import com.workbuddy.common.security.KnowledgeUser;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.SecurityUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 操作审计数据访问服务实现：审计行组装内聚在此，append-only。
 */
@Slf4j
@Service
public class WbAuditLogDbServiceImpl extends InfraDbServiceImpl<WbAuditLogMapper, WbAuditLog>
        implements WbAuditLogDbService {

    /** 无认证上下文（后台线程/测试）时的操作人兜底 */
    private static final String FALLBACK_OPERATOR = "system";

    /**
     * 写一条审计日志：操作人取当前登录用户，无认证上下文时用兜底操作人。
     *
     * @param action     动作类型
     * @param objectType 对象类型码值
     * @param objectId   对象 ID
     * @param payload    操作载荷（JSON，可空）
     */
    @Override
    public void saveAudit(AuditActionType action, String objectType, Long objectId, String payload) {
        KnowledgeUser user = currentUser();
        WbAuditLog audit = new WbAuditLog();
        audit.setActionType(action.name());
        audit.setObjectType(objectType);
        audit.setObjectId(String.valueOf(objectId));
        audit.setPayload(payload);
        audit.setUserId(NullUtil.isNull(user) ? null : user.getId());
        audit.setCreateBy(operatorOf(user));
        save(audit);
    }

    /** 审计操作人：登录用户名优先；无认证上下文回退 system */
    private String operatorOf(KnowledgeUser user) {
        if (NullUtil.isNotNull(user) && StrUtil.isNotBlank(user.getUsername())) {
            return user.getUsername();
        }
        return FALLBACK_OPERATOR;
    }

    /** 取当前登录用户：无认证时可能为 null 或抛异常，统一防御 */
    private KnowledgeUser currentUser() {
        try {
            return SecurityUtil.getUser();
        } catch (Exception e) {
            log.warn("获取当前登录用户失败，审计操作人回退 system", e);
            return null;
        }
    }
}
