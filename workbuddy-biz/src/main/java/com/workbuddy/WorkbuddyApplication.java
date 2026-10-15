package com.workbuddy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@MapperScan({"com.workbuddy.biz.mapper", "com.workbuddy.auth.mapper", "com.workbuddy.filecenter.mapper"})
public class WorkbuddyApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkbuddyApplication.class, args);
    }
}
