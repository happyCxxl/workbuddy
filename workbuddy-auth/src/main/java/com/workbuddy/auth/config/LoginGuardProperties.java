package com.workbuddy.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录失败防护配置（前缀 knowledge.auth.guard）。
 *
 * @author cxxl
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.auth.guard")
public class LoginGuardProperties {

    /** 是否启用登录失败防护 */
    private boolean enabled = true;

    /** 同一「账号 + IP」允许的连续失败次数 */
    private int failThreshold = 5;

    /** 同一 IP 允许的连续失败次数（跨账号，防喷洒撞库） */
    private int ipFailThreshold = 20;

    /** 计数窗口与锁定时长（分钟）：窗口自首次失败起算，期间不再续期 */
    private int lockMinutes = 15;
}
