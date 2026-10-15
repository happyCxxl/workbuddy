package com.workbuddy.biz.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.common.domain.entity.KbEmbeddingSet;
import com.workbuddy.common.domain.entity.KbFileObject;
import com.workbuddy.common.domain.entity.KbPipelineProduct;
import com.workbuddy.common.domain.entity.KbStorageSource;
import com.workbuddy.common.domain.entity.User;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储列映射契约测试：实体字段名与库表列名的对应关系。
 *
 * <p>列名由 MyBatis-Plus 按驼峰转下划线推导，字段名写错不会编译失败，只在运行期报「Unknown column」；
 * 这里把数据源表与五处对象引用点的列名钉住。
 *
 * @author cxxl
 */
class StorageEntityColumnMappingTest {

    private static final List<Class<?>> ENTITIES = List.of(KbStorageSource.class, KbFileObject.class,
            KbPipelineProduct.class, KbChunkSet.class, KbEmbeddingSet.class, User.class);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        ENTITIES.forEach(entity -> TableInfoHelper.initTableInfo(assistant, entity));
    }

    /** 实体属性名对应的列名；未映射返回 null */
    private static String columnOf(Class<?> entity, String property) {
        TableInfo info = TableInfoHelper.getTableInfo(entity);
        assertNotNull(info, "实体未登记表信息: " + entity.getSimpleName());
        return info.getFieldList().stream()
                .filter(field -> property.equals(field.getProperty()))
                .map(TableFieldInfo::getColumn)
                .findFirst()
                .orElse(null);
    }

    @Test
    void storageSourceColumnsShouldMatchSchema() {
        TableInfo info = TableInfoHelper.getTableInfo(KbStorageSource.class);

        assertEquals("kb_storage_source", info.getTableName());
        assertEquals("id", info.getKeyColumn());
        assertEquals("name", columnOf(KbStorageSource.class, "name"));
        assertEquals("storage_type", columnOf(KbStorageSource.class, "storageType"));
        assertEquals("config_json", columnOf(KbStorageSource.class, "configJson"));
        assertEquals("is_current", columnOf(KbStorageSource.class, "isCurrent"));
        assertEquals("status", columnOf(KbStorageSource.class, "status"));
        assertEquals("last_probe_ok", columnOf(KbStorageSource.class, "lastProbeOk"));
        assertEquals("last_probe_at", columnOf(KbStorageSource.class, "lastProbeAt"));
        assertEquals("del_flag", columnOf(KbStorageSource.class, "delFlag"));
        assertEquals("create_by", columnOf(KbStorageSource.class, "createBy"));
        assertEquals("create_time", columnOf(KbStorageSource.class, "createTime"));
        assertEquals("update_by", columnOf(KbStorageSource.class, "updateBy"));
        assertEquals("update_time", columnOf(KbStorageSource.class, "updateTime"));
    }

    @Test
    void storageSourceShouldUseLogicDelete() {
        TableInfo info = TableInfoHelper.getTableInfo(KbStorageSource.class);

        assertTrue(info.isWithLogicDelete(), "存储数据源按逻辑删除处理");
        assertEquals("del_flag", info.getLogicDeleteFieldInfo().getColumn());
        assertEquals("1", info.getLogicDeleteFieldInfo().getLogicDeleteValue());
        assertEquals("0", info.getLogicDeleteFieldInfo().getLogicNotDeleteValue());
    }

    @Test
    void objectTablesShouldCarryStorageSourceIdColumn() {
        assertEquals("storage_source_id", columnOf(KbFileObject.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(KbPipelineProduct.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(KbChunkSet.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(KbEmbeddingSet.class, "storageSourceId"));
        assertEquals("avatar_storage_source_id", columnOf(User.class, "avatarStorageSourceId"));
    }

    @Test
    void objectTablesShouldKeepStorageTypeColumn() {
        assertEquals("storage_type", columnOf(KbFileObject.class, "storageType"));
        assertEquals("storage_type", columnOf(KbPipelineProduct.class, "storageType"));
        assertEquals("storage_type", columnOf(KbChunkSet.class, "storageType"));
        assertEquals("storage_type", columnOf(KbEmbeddingSet.class, "storageType"));
        assertEquals("kb_file_object", TableInfoHelper.getTableInfo(KbFileObject.class).getTableName());
        assertEquals("kb_user", TableInfoHelper.getTableInfo(User.class).getTableName());
    }
}
