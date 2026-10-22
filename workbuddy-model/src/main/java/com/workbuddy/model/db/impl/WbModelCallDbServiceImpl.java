package com.workbuddy.model.db.impl;

import com.workbuddy.common.domain.entity.WbModelCall;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import com.workbuddy.model.db.WbModelCallDbService;
import com.workbuddy.model.mapper.WbModelCallMapper;
import org.springframework.stereotype.Service;

/**
 * 模型调用明细数据访问实现。
 */
@Service
public class WbModelCallDbServiceImpl extends InfraDbServiceImpl<WbModelCallMapper, WbModelCall>
        implements WbModelCallDbService {
}
