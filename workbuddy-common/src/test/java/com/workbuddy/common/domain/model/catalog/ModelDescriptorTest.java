package com.workbuddy.common.domain.model.catalog;

import com.workbuddy.common.enums.model.ModelCapability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型目录视图单测。
 *
 * <p>守护两条判定：{@code enabled} 忽略大小写且空状态为未启用；{@code supports} 对空清单返回 false。
 */
class ModelDescriptorTest {

    /**
     * 启用判定：忽略大小写，空/停用为 false。
     *
     * <p>守护 {@code enabled}：判定写反会让停用模型进入候选。
     */
    @Test
    void enabledShouldIgnoreCaseAndRejectBlank() {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.setStatus("enabled");
        assertTrue(descriptor.enabled());

        descriptor.setStatus("DISABLED");
        assertFalse(descriptor.enabled());

        descriptor.setStatus(null);
        assertFalse(descriptor.enabled());
    }

    /**
     * 能力判定：清单含该能力为 true，空清单为 false。
     *
     * <p>守护 {@code supports}：空清单被当成"全支持"会让路由选错模型。
     */
    @Test
    void supportsShouldRequireListedCapability() {
        ModelDescriptor descriptor = new ModelDescriptor();
        descriptor.getCapabilities().add(ModelCapability.CHAT);
        assertTrue(descriptor.supports(ModelCapability.CHAT));
        assertFalse(descriptor.supports(ModelCapability.EMBEDDING));

        descriptor.setCapabilities(null);
        assertFalse(descriptor.supports(ModelCapability.CHAT));
    }
}
