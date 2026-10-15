package com.workbuddy.model.startup;

import com.workbuddy.common.domain.model.AuthType;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.startup.StartupCheck;
import com.workbuddy.common.startup.StartupCheckChain;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.model.config.ModelVendorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 厂商配置启动期校验：逐个检查配置里列出的厂商，任一项不满足即终止启动。
 *
 * <p>只校验配置里列出的厂商；{@code knowledge.model.vendors} 整段缺省即直接放行，本地与 CI 无需密钥即可启动。
 * 错误信息带厂商码与配置键名。日志不打印密钥。厂商数量不设上限：嵌入入口按目录解析「模型 → 厂商」，
 * 多厂商配置与单厂商配置都直接放行。
 *
 * <p>在启动校验链里排在 {@link #ORDER_CREDENTIALS} 档：它依赖密钥等外部凭据。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelVendorValidator implements StartupCheck {

    /** 当前支持的鉴权方式集合 */
    private static final Set<AuthType> SUPPORTED_AUTH_TYPES = Set.of(AuthType.BEARER);

    /** 超时留空时的默认值（毫秒） */
    private static final int DEFAULT_TIMEOUT_MS = 60000;

    private final ModelVendorProperties properties;

    @Override
    public int order() {
        return ORDER_CREDENTIALS;
    }

    @Override
    public void check(StartupCheckChain next) {
        validate(properties);
        next.proceed();
    }

    /**
     * 校验厂商配置；厂商集合为空直接通过，首个不通过的厂商即抛出。
     *
     * @param properties 厂商配置
     */
    public void validate(ModelVendorProperties properties) {
        Map<String, ModelVendorProperties.Vendor> vendors = properties.getVendors();
        if (NullUtil.isNull(vendors) || vendors.isEmpty()) {
            log.info("模型厂商配置为空，跳过启动期校验");
            return;
        }
        StringJoiner codes = new StringJoiner(", ");
        for (Map.Entry<String, ModelVendorProperties.Vendor> entry : vendors.entrySet()) {
            validateVendor(entry.getKey(), entry.getValue());
            codes.add(entry.getKey());
        }
        log.info("模型厂商配置校验通过, vendors=[{}]", codes);
    }

    /** 校验单个厂商；厂商码缺失或配置项非法即抛 KnowledgeException */
    private void validateVendor(String vendorCode, ModelVendorProperties.Vendor vendor) {
        ThrowUtil.throwIf(NullUtil.isNull(vendorCode) || vendorCode.isBlank(), ErrorCode.MODEL_VENDOR_UNKNOWN,
                "厂商码不能为空（配置键 knowledge.model.vendors）");
        ThrowUtil.throwIf(NullUtil.isNull(vendor), ErrorCode.MODEL_VENDOR_UNKNOWN,
                "厂商 " + vendorCode + " 缺配置体（配置键 " + configKey(vendorCode) + "）");
        ThrowUtil.throwIf(vendor.getAuthType() == null || !SUPPORTED_AUTH_TYPES.contains(vendor.getAuthType()),
                ErrorCode.PARAM_INVALID,
                "厂商 " + vendorCode + " 的 auth-type 取值 " + vendor.getAuthType() + " 不在支持集 " + SUPPORTED_AUTH_TYPES
                        + "（配置键 " + configKey(vendorCode) + ".auth-type）");
        ThrowUtil.throwIf(NullUtil.isNull(vendor.getApiKey()) || vendor.getApiKey().isBlank(),
                ErrorCode.MODEL_AUTH_FAILED,
                "厂商 " + vendorCode + " 缺 api-key（配置键 " + configKey(vendorCode)
                        + ".api-key），yml 里写 ${ENV_VAR:} 占位，真实值走环境变量");
        Integer timeoutMs = vendor.getTimeoutMs();
        ThrowUtil.throwIf(NullUtil.isNotNull(timeoutMs) && timeoutMs <= 0, ErrorCode.PARAM_INVALID,
                "厂商 " + vendorCode + " 的 timeout-ms 必须为正数，当前值 " + timeoutMs
                        + "（配置键 " + configKey(vendorCode) + ".timeout-ms，留空取 " + DEFAULT_TIMEOUT_MS + "）");
        String baseUrl = vendor.getBaseUrl();
        ThrowUtil.throwIf(NullUtil.isNotNull(baseUrl) && !isBaseUrlUsable(baseUrl), ErrorCode.PARAM_INVALID,
                "厂商 " + vendorCode + " 的 base-url 不是合法 http/https 地址，当前值 " + baseUrl
                        + "（配置键 " + configKey(vendorCode) + ".base-url，留空取适配器内置默认地址）");
    }

    /** 留空即用内置默认地址，判定为可用；给了值必须是带主机的 http/https 绝对地址 */
    private boolean isBaseUrlUsable(String baseUrl) {
        if (baseUrl.isBlank()) {
            return true;
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = NullUtil.isNull(uri.getScheme()) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && NullUtil.isNotNull(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** 厂商配置键全名（错误信息里指出改哪一项） */
    private String configKey(String vendorCode) {
        return "knowledge.model.vendors." + vendorCode;
    }
}
