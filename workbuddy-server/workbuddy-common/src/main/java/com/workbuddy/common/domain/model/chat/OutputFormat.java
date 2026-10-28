package com.workbuddy.common.domain.model.chat;

/**
 * 输出格式：TEXT 原样文本；JSON_OBJECT 只要求合法 JSON 对象；JSON_SCHEMA 还要满足给定 schema。
 */
public enum OutputFormat {

    /** 原样文本，不做结构校验 */
    TEXT,

    /** 合法 JSON 对象 */
    JSON_OBJECT,

    /** 满足给定 JSON Schema 的对象 */
    JSON_SCHEMA
}
