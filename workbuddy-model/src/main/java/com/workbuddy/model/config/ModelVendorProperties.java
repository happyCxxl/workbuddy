package com.workbuddy.model.config;

import com.workbuddy.common.enums.model.AuthType;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/**
 * 模型厂商连接配置（配置键 `workbuddy.model.vendors.<厂商码>`，厂商码即 Map 键）。
 *
 * <p>整段可缺省：不配即没有厂商进入运行时，本地与 CI 无需密钥即可启动。字段留空的口径见各字段说明。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workbuddy.model")
public class ModelVendorProperties {

    /** 厂商码 → 连接配置；按厂商码排序，校验与日志的遍历顺序固定 */
    private Map<String, Vendor> vendors;

    /**
     * 单个厂商的连接配置。
     */
    @Data
    public static class Vendor {

        /** 服务地址；留空 = 用适配器内置默认地址 */
        private String baseUrl;

        /** 鉴权方式；当前支持集只有 {@link AuthType#BEARER}，启动期校验 */
        private AuthType authType;

        /** 密钥；yml 里写 ${ENV_VAR:} 占位，真实值走环境变量 */
        private String apiKey;

        /** 单一总超时（毫秒）；留空 = 60000 */
        private Integer timeoutMs;

        /** 同一实例内的重试次数；留空 = 用底层默认值 */
        private Integer maxRetries;

        /** 展示名（清单页显示用）；留空 = 用厂商码 */
        private String displayName;
    }
}
