package com.workbuddy.model.db;

import com.workbuddy.common.domain.entity.WbModelCall;
import com.workbuddy.infra.persistence.InfraDbService;

/**
 * 模型调用明细数据访问（表：wb_model_call）：只插入，不更新。
 */
public interface WbModelCallDbService extends InfraDbService<WbModelCall> {
}
