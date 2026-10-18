package com.workbuddy.filecenter.provider;

import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储路由：按数据源实例给出存储提供者，并持有当前启用的数据源。
 *
 * <p>**注册表**（{@link #register(StorageSourceDef)}）：业务层在启动初始化与数据源增删改时登记定义，
 * 参数变更后重新注册会让旧的提供者实例失效并在下次取用时按新参数重建。
 *
 * <p>**当前启用**（{@link #currentSourceId()}）：业务层标记的那一个，新对象写入走它；
 * **读取**按记录里的数据源 ID（{@link #of(ObjectRef)}），与当前启用的数据源无关。
 * 未注册的 ID 抛 40455，不回落成当前启用的数据源。
 */
@Slf4j
@Component
public class StorageRouter {

    /** 已注册的数据源定义 */
    private final Map<Long, StorageSourceDef> defs = new ConcurrentHashMap<>();

    /** 已创建的数据源提供者（首次取用时构造并初始化） */
    private final Map<Long, StorageProvider> providers = new ConcurrentHashMap<>();

    /** 当前启用的数据源 ID */
    private volatile Long currentSourceId;

    /**
     * 注册数据源定义。
     *
     * @param def 数据源定义
     */
    public void register(StorageSourceDef def) {
        defs.put(def.id(), def);
        providers.remove(def.id());
        log.info("===> StorageRouter register 注册数据源, id={}, name={}, type={}",
                def.id(), def.name(), def.type().getCode());
    }

    /**
     * 注销数据源定义并丢弃已创建的提供者；注销的是当前启用的数据源时一并清除当前启用标记。
     *
     * @param sourceId 数据源 ID（可空，为空时不动作）
     */
    public void unregister(Long sourceId) {
        if (NullUtil.isNull(sourceId)) {
            return;
        }
        defs.remove(sourceId);
        providers.remove(sourceId);
        if (sourceId.equals(currentSourceId)) {
            currentSourceId = null;
        }
        log.info("===> StorageRouter unregister 注销数据源, id={}", sourceId);
    }

    /**
     * 标记当前启用的数据源：先取后端完成探活（建桶或建根目录），成功后替换当前启用标记。
     *
     * @param sourceId 目标数据源 ID
     * @throws KnowledgeException 该 ID 未注册或其参数不齐（40455）
     */
    public void markCurrent(Long sourceId) {
        StorageProvider provider = of(sourceId);
        this.currentSourceId = sourceId;
        StorageSourceDef def = defs.get(sourceId);
        log.info("===> StorageRouter markCurrent 当前启用的数据源 = {}（id={}, type={}, backend={}）",
                def.name(), sourceId, def.type().getCode(), provider.type().getCode());
    }

    /**
     * 当前启用的数据源 ID。
     *
     * @return 数据源 ID；未启用任何数据源时为 null
     */
    public Long currentSourceId() {
        return currentSourceId;
    }

    /**
     * 当前启用的数据源定义。
     *
     * @return 数据源定义；未启用任何数据源时为 null
     */
    public StorageSourceDef currentDef() {
        return NullUtil.isNull(currentSourceId) ? null : defs.get(currentSourceId);
    }

    /**
     * 当前启用数据源的存储类型。
     *
     * @return 存储类型；未启用任何数据源时为 null
     */
    public StorageType writeType() {
        StorageSourceDef def = currentDef();
        return NullUtil.isNull(def) ? null : def.type();
    }

    /**
     * 数据源名称。
     *
     * @param sourceId 数据源 ID
     * @return 名称；该 ID 未注册时为 null
     */
    public String nameOf(Long sourceId) {
        StorageSourceDef def = NullUtil.isNull(sourceId) ? null : defs.get(sourceId);
        return NullUtil.isNull(def) ? null : def.name();
    }

    /**
     * 当前启用数据源的名称。
     *
     * @return 名称；未启用任何数据源或该 ID 未注册时为 null
     */
    public String currentName() {
        return nameOf(currentSourceId);
    }

    /**
     * 数据源的存储类型。
     *
     * @param sourceId 数据源 ID
     * @return 存储类型；该 ID 未注册时为 null
     */
    public StorageType typeOf(Long sourceId) {
        StorageSourceDef def = NullUtil.isNull(sourceId) ? null : defs.get(sourceId);
        return NullUtil.isNull(def) ? null : def.type();
    }

    /**
     * 数据源是否已注册。
     *
     * @param sourceId 数据源 ID
     * @return 已注册为 true
     */
    public boolean registered(Long sourceId) {
        return NullUtil.isNotNull(sourceId) && defs.containsKey(sourceId);
    }

    /**
     * 当前启用数据源的文件对象桶名。
     *
     * @return 桶名
     * @throws KnowledgeException 未启用任何数据源（40455）
     */
    public String currentFileBucket() {
        return requireCurrentDef().fileBucket();
    }

    /**
     * 当前启用数据源的内容寻址对象桶名。
     *
     * @return 桶名
     * @throws KnowledgeException 未启用任何数据源（40455）
     */
    public String currentArtifactBucket() {
        return requireCurrentDef().artifactBucket();
    }

    /**
     * 新对象的写入后端。
     *
     * @return 当前启用数据源对应的存储后端
     * @throws KnowledgeException 未启用任何数据源（40455）
     */
    public StorageProvider writer() {
        return of(requireCurrentDef().id());
    }

    /**
     * 按数据源 ID 取后端。
     *
     * @param sourceId 数据源 ID
     * @return 该数据源的提供者（首次调用时构造并初始化）
     * @throws KnowledgeException ID 为空或未注册（40455）
     */
    public StorageProvider of(Long sourceId) {
        ThrowUtil.throwIf(NullUtil.isNull(sourceId), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "当前没有启用的存储数据源");
        ThrowUtil.throwIf(!defs.containsKey(sourceId), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "存储数据源未注册: sourceId=" + sourceId);
        return providers.computeIfAbsent(sourceId, this::create);
    }

    /**
     * 按对象位置取后端。
     *
     * @param ref 对象位置
     * @return 该位置的存储后端
     * @throws KnowledgeException 位置里的数据源为空或未注册（40455）
     */
    public StorageProvider of(ObjectRef ref) {
        return of(ref.sourceId());
    }

    /** 当前启用的数据源定义；未启用时抛 40455 */
    private StorageSourceDef requireCurrentDef() {
        StorageSourceDef def = currentDef();
        ThrowUtil.throwIf(NullUtil.isNull(def), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "当前没有启用的存储数据源");
        return def;
    }

    /** 构造并初始化提供者：定义在构造与初始化之间被换掉时按未注册处理 */
    private StorageProvider create(Long sourceId) {
        StorageSourceDef def = defs.get(sourceId);
        ThrowUtil.throwIf(NullUtil.isNull(def), ErrorCode.STORAGE_BACKEND_UNCONFIGURED,
                "存储数据源未注册: sourceId=" + sourceId);
        StorageProvider provider = StorageProviderFactory.create(def);
        provider.initialize();
        return provider;
    }
}
