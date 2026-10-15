package com.workbuddy.model.engine;

import com.workbuddy.common.domain.model.catalog.ModelDescriptor;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.catalog.ModelCatalogPort;
import com.workbuddy.model.config.ModelVendorProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型实例注册表：按厂商码 + 模型名缓存 LC4j 实例，懒构建、命中复用。
 * 缓存键为厂商码与模型名的字符串拼接（分隔符取竖线，两类标识都不含竖线），对话与向量各一张表。
 *
 * <p>厂商归属优先取模型目录：按模型名查到唯一启用模型即用它的厂商码；目录未收录该模型时，
 * 回落到「配置里只有一个厂商即用该厂商」的兜底口径（多厂商时无法判定，按未配置厂商拒绝）。
 *
 * @author cxxl
 */
@Component
@RequiredArgsConstructor
public class ModelInstanceRegistry {

    /** 缓存键分隔符 */
    private static final String KEY_SEPARATOR = "|";

    private final ModelVendorProperties vendorProperties;

    private final OpenAiCompatibleEngineFactory engineFactory;

    private final ModelCatalogPort modelCatalog;

    /** 对话模型实例（键 = 厂商码|模型名） */
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();

    /** 向量模型实例（键 = 厂商码|模型名） */
    private final Map<String, EmbeddingModel> embeddingModels = new ConcurrentHashMap<>();

    /**
     * 对话模型实例：同键命中即复用已建实例；厂商未配置或模型名为空即抛 KnowledgeException。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return LC4j 对话模型实例
     */
    public ChatModel chatModel(String vendorCode, String modelKey) {
        ModelVendorProperties.Vendor vendor = vendor(vendorCode);
        checkModelKey(vendorCode, modelKey);
        return chatModels.computeIfAbsent(key(vendorCode, modelKey), cacheKey -> engineFactory.chatModel(vendor, modelKey));
    }

    /**
     * 向量模型实例：厂商按模型目录解析（模型 → 厂商），缓存口径与对话模型一致。
     *
     * @param modelKey 模型名
     * @return LC4j 向量模型实例
     */
    public EmbeddingModel embeddingModel(String modelKey) {
        return embeddingModel(resolveVendorCode(modelKey), modelKey);
    }

    /**
     * 向量模型实例：厂商码由形参给定，缓存口径与对话模型一致。
     *
     * @param vendorCode 厂商码
     * @param modelKey   模型名
     * @return LC4j 向量模型实例
     */
    public EmbeddingModel embeddingModel(String vendorCode, String modelKey) {
        ModelVendorProperties.Vendor vendor = vendor(vendorCode);
        checkModelKey(vendorCode, modelKey);
        return embeddingModels.computeIfAbsent(key(vendorCode, modelKey),
                cacheKey -> engineFactory.embeddingModel(vendor, modelKey));
    }

    /** 已建实例总数（对话 + 向量），供测试与排障查询 */
    public int instanceCount() {
        return chatModels.size() + embeddingModels.size();
    }

    /** 指定厂商码与模型名的对话实例是否已建，供测试与排障查询 */
    public boolean chatInstanceBuilt(String vendorCode, String modelKey) {
        return chatModels.containsKey(key(vendorCode, modelKey));
    }

    /** 指定厂商码与模型名的向量实例是否已建，供测试与排障查询 */
    public boolean embeddingInstanceBuilt(String vendorCode, String modelKey) {
        return embeddingModels.containsKey(key(vendorCode, modelKey));
    }

    /** 缓存键：厂商码 + 分隔符 + 模型名 */
    private String key(String vendorCode, String modelKey) {
        return vendorCode + KEY_SEPARATOR + modelKey;
    }

    /**
     * 解析向量模型的厂商：目录里唯一启用模型带出厂商码即用它；目录未收录该模型时，
     * 配置里恰好一个厂商即用该厂商，零个或多个厂商都按「模型厂商未配置」拒绝（判定不出该找哪一家）。
     *
     * @param modelKey 模型名
     * @return 厂商码
     */
    public String resolveVendorCode(String modelKey) {
        checkModelKey(null, modelKey);
        ModelDescriptor descriptor = modelCatalog.findUniqueEnabledByModelKey(modelKey);
        if (NullUtil.isNotNull(descriptor) && NullUtil.isNotNull(descriptor.getVendorCode())
                && !descriptor.getVendorCode().isBlank()) {
            return descriptor.getVendorCode();
        }
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        Set<String> codes = NullUtil.isNull(vendors) ? Set.of() : vendors.keySet();
        if (codes.size() != 1) {
            throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN,
                    "模型：" + modelKey + " 未在目录中唯一命中，已配置厂商：" + codes);
        }
        return codes.iterator().next();
    }

    /** 取厂商配置；配置里没有该厂商即抛「模型厂商未配置」，消息带该厂商码与已配置厂商清单 */
    private ModelVendorProperties.Vendor vendor(String vendorCode) {
        Map<String, ModelVendorProperties.Vendor> vendors = vendorProperties.getVendors();
        ModelVendorProperties.Vendor vendor = NullUtil.isNull(vendors) ? null : vendors.get(vendorCode);
        if (NullUtil.isNull(vendor)) {
            throw new KnowledgeException(ErrorCode.MODEL_VENDOR_UNKNOWN,
                    "厂商：" + vendorCode + "，已配置厂商：" + (NullUtil.isNull(vendors) ? "[]" : vendors.keySet()));
        }
        return vendor;
    }

    /** 模型名缺失即拒绝调用；厂商码既可为空（目录解析路径），也可为已解析出的厂商码 */
    private void checkModelKey(String vendorCode, String modelKey) {
        if (NullUtil.isNull(modelKey) || modelKey.isBlank()) {
            throw new KnowledgeException(ErrorCode.MODEL_BAD_REQUEST, "模型名不能为空，厂商：" + vendorCode);
        }
    }
}
