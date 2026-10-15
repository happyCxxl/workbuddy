package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.strategy.StrategyVersionCreateDto;
import com.workbuddy.common.dto.request.strategy.StrategyVersionUpdateDto;
import com.workbuddy.common.dto.response.strategy.StrategyVersionVO;
import com.workbuddy.common.enums.strategy.StrategyType;

import java.util.List;
import java.util.Set;

/**
 * 策略版本服务：切片策略的下拉查询与管理（预处理/向量化等类型随对应环节加入）。
 * 口径：策略版本行不可变——编辑 = 复制新行；停用代替删除（有绑定引用禁物理删除）。
 *
 * @author cxxl
 */
public interface StrategyVersionService {

    /** 已支持管理/列表/注册的策略类型（其余类型随环节实现加入）；码值口径见 {@link StrategyType} */
    Set<String> SUPPORTED_TYPES = StrategyType.keys();

    /** KB 三件套可绑定类型（检索规则不绑 KB——走索引版本行 default_rule_id） */
    Set<String> BINDABLE_TYPES = StrategyType.bindableKeys();

    /**
     * 列表：includeInactive=false 只启用中（触发前下拉用），true 全部（管理用）；空/非法类型 40001。
     */
    List<StrategyVersionVO> list(String type, boolean includeInactive);

    /**
     * 创建（默认 ACTIVE；type 白名单校验；同环节同名版本撞唯一键 → 40001）。
     */
    StrategyVersionVO create(StrategyVersionCreateDto dto);

    /**
     * 编辑 = 复制新版本（策略版本行不可变）：type 归属旧行不变；新行按 dto 的 name/version/configSnapshot
     * 注册（撞唯一键 40001），旧行原样保留；不存在 40433。
     */
    StrategyVersionVO update(Long id, StrategyVersionUpdateDto dto);

    /**
     * 启用/停用（幂等成功；不存在 40433）。
     */
    StrategyVersionVO enable(Long id);

    StrategyVersionVO disable(Long id);

    /**
     * 物理删除（被知识库绑定引用 → 40452 拒绝，停用代替删除；无引用可删；不存在 40433）。
     */
    boolean delete(Long id);
}
