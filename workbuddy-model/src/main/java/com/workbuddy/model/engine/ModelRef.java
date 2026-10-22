package com.workbuddy.model.engine;

/**
 * 模型运行时定位：厂商码 + 厂商侧模型名。
 *
 * @param vendorCode 厂商码
 * @param modelKey   厂商侧模型名
 */
public record ModelRef(String vendorCode, String modelKey) {
}
