package com.workbuddy.model.registry;

import com.workbuddy.common.domain.model.RegisteredModel;
import com.workbuddy.common.enums.model.ModelCapability;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 模型内存注册表：录入模型（wb_model）中启用行的运行时快照。
 *
 * <p>biz 装载器在启动与刷新时经 {@link #reload(List)} 全量推入（多实例经 Redis 广播各自重载）；
 * 停用行的过滤在装载侧完成，快照里的行一律是启用行。
 * 查询面随 chat 选路层定形后开放，本类不预先提供查询方法。
 */
@Component
public class ModelRegistry {

    /** 启用行快照；reload 整体换引用，读侧无锁 */
    private volatile List<RegisteredModel> models = List.of();

    /**
     * 全量替换注册表快照。
     *
     * @param models 装载器读出的启用行，可空
     */
    public void reload(List<RegisteredModel> models) {
        this.models = NullUtil.isNull(models)
                ? List.of()
                : models.stream()
                        .filter(model -> NullUtil.isNotNull(model) && NullUtil.isNotNull(model.getModelKey()))
                        .toList();
    }

    /**
     * 快照全量视图（启动日志与单测观测用）。
     *
     * @return 当前快照（不可变，可为空）
     */
    public List<RegisteredModel> all() {
        return models;
    }

    /**
     * 按能力取启用行（注册表只装启用行）。
     *
     * @param capability 能力，可空
     * @return 含该能力的行（保持注册表顺序）；能力为空时为空列表
     */
    public List<RegisteredModel> byCapability(ModelCapability capability) {
        if (NullUtil.isNull(capability)) {
            return List.of();
        }
        return models.stream()
                .filter(model -> NullUtil.isNotNull(model.getCapabilities())
                        && model.getCapabilities().contains(capability))
                .toList();
    }

    /**
     * 按厂商码与模型名取一行。
     *
     * @param vendorCode 厂商码，可空
     * @param modelKey   厂商侧模型名，可空
     * @return 命中行；参数为空或未命中时为空
     */
    public Optional<RegisteredModel> find(String vendorCode, String modelKey) {
        if (NullUtil.isNull(vendorCode) || NullUtil.isNull(modelKey)) {
            return Optional.empty();
        }
        return models.stream()
                .filter(model -> vendorCode.equals(model.getVendorCode()) && modelKey.equals(model.getModelKey()))
                .findFirst();
    }
}
