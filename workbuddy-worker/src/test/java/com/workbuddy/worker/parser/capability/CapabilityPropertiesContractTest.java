package com.workbuddy.worker.parser.capability;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 能力配置面的契约单测：`knowledge.parse.capability` 只留三项开关（模型侧地址与超时不在本前缀下），
 * 字段清单按名字逐个比对。
 *
 * @author cxxl
 */
class CapabilityPropertiesContractTest {

    /** 三项开关的字段名（与 yml 的 kebab 键一一对应） */
    private static final List<String> SWITCHES = List.of("ocrEnabled", "layoutEnabled", "tableEnabled");

    @Test
    void propertiesShouldKeepOnlyThreeSwitches() {
        List<String> names = new ArrayList<>();
        for (Field field : CapabilityProperties.class.getDeclaredFields()) {
            assertEquals("boolean", field.getType().getSimpleName(), () -> "开关类型: " + field.getName());
            names.add(field.getName());
        }

        assertEquals(SWITCHES, names);
    }

    @Test
    void propertiesShouldNotCarryConnectionParameters() {
        for (Field field : CapabilityProperties.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase();
            assertFalse(name.contains("url"), () -> "连接地址不进解析侧配置: " + field.getName());
            assertFalse(name.contains("timeout"), () -> "超时参数不在解析侧: " + field.getName());
        }
    }
}
