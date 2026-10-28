package com.workbuddy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


/**
 * 应用启动入口：装配 Spring 容器并扫描各模块的映射接口。
 */
@SpringBootApplication
@MapperScan({
        "com.workbuddy.biz.mapper",
        "com.workbuddy.auth.mapper",
        "com.workbuddy.filecenter.mapper",
        "com.workbuddy.model.mapper"
})
public class WorkbuddyApplication {

    /**
     * 启动应用。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(WorkbuddyApplication.class, args);
    }
}
