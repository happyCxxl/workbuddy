package com.workbuddy.infra.vector;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.workbuddy.common.utils.NullUtil;

import java.util.Map;

/**
 * Milvus 行编解码：领域行 ↔ 写入 JSON / 查询实体。
 */
final class MilvusRowCodec {

    private MilvusRowCodec() {
    }

    /**
     * 行 → Milvus 行 JSON（字段名与 schema 一致；null 归一化）。
     *
     * @param row 领域行
     * @return JSON 行
     */
    static JsonObject toJson(CollectionRow row) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", row.getId());
        obj.addProperty("document_id", row.getDocumentId());
        obj.addProperty("owner", ObjectUtil.defaultIfNull(row.getOwner(), ""));
        obj.addProperty("content_type", row.getContentType());
        addNullable(obj, "parent_chunk_id", row.getParentChunkId());
        obj.addProperty("content", row.getContent());
        obj.addProperty("title_path", ObjectUtil.defaultIfNull(row.getTitlePath(), ""));
        addNullable(obj, "source_element_ids", row.getSourceElementIds());
        JsonArray vector = new JsonArray();
        for (Float f : row.getVector()) {
            vector.add(f);
        }
        obj.add("vector", vector);
        return obj;
    }

    /**
     * 查询实体 → 领域行。
     *
     * @param entity 查询实体
     * @return 领域行
     */
    static CollectionRow toRow(Map<String, Object> entity) {
        CollectionRow row = new CollectionRow();
        fill(row, entity);
        return row;
    }

    /**
     * 查询实体 → 带分数命中行。
     *
     * @param entity 查询实体
     * @return 命中行
     */
    static ScoredRow toScoredRow(Map<String, Object> entity) {
        ScoredRow row = new ScoredRow();
        fill(row, entity);
        return row;
    }

    private static void addNullable(JsonObject obj, String key, String value) {
        if (StrUtil.isBlank(value)) {
            obj.add(key, JsonNull.INSTANCE);
        } else {
            obj.addProperty(key, value);
        }
    }

    private static void fill(CollectionRow row, Map<String, Object> entity) {
        row.setId(str(entity.get("id")));
        row.setDocumentId(longOf(entity.get("document_id")));
        row.setOwner(str(entity.get("owner")));
        row.setContentType(str(entity.get("content_type")));
        row.setParentChunkId(str(entity.get("parent_chunk_id")));
        row.setContent(str(entity.get("content")));
        row.setTitlePath(str(entity.get("title_path")));
        row.setSourceElementIds(str(entity.get("source_element_ids")));
    }

    private static String str(Object value) {
        return NullUtil.isNull(value) ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (NullUtil.isNull(value)) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(String.valueOf(value));
    }
}
