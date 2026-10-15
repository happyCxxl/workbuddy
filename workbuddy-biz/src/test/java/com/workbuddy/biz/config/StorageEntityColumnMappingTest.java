package com.workbuddy.biz.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbStorageSource;
import com.workbuddy.common.domain.entity.WbUser;
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

    private static final List<Class<?>> ENTITIES = List.of(WbStorageSource.class, WbFileObject.class,
            WbPipelineProduct.class, WbChunkSet.class, WbEmbeddingSet.class, WbUser.class);

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
        TableInfo info = TableInfoHelper.getTableInfo(WbStorageSource.class);

        assertEquals("wb_storage_source", info.getTableName());
        assertEquals("id", info.getKeyColumn());
        assertEquals("name", columnOf(WbStorageSource.class, "name"));
        assertEquals("storage_type", columnOf(WbStorageSource.class, "storageType"));
        assertEquals("config_json", columnOf(WbStorageSource.class, "configJson"));
        assertEquals("is_current", columnOf(WbStorageSource.class, "isCurrent"));
        assertEquals("status", columnOf(WbStorageSource.class, "status"));
        assertEquals("last_probe_ok", columnOf(WbStorageSource.class, "lastProbeOk"));
        assertEquals("last_probe_at", columnOf(WbStorageSource.class, "lastProbeAt"));
        assertEquals("del_flag", columnOf(WbStorageSource.class, "delFlag"));
        assertEquals("create_by", columnOf(WbStorageSource.class, "createBy"));
        assertEquals("create_time", columnOf(WbStorageSource.class, "createTime"));
        assertEquals("update_by", columnOf(WbStorageSource.class, "updateBy"));
        assertEquals("update_time", columnOf(WbStorageSource.class, "updateTime"));
    }

    @Test
    void storageSourceShouldUseLogicDelete() {
        TableInfo info = TableInfoHelper.getTableInfo(WbStorageSource.class);

        assertTrue(info.isWithLogicDelete(), "存储数据源按逻辑删除处理");
        assertEquals("del_flag", info.getLogicDeleteFieldInfo().getColumn());
        assertEquals("1", info.getLogicDeleteFieldInfo().getLogicDeleteValue());
        assertEquals("0", info.getLogicDeleteFieldInfo().getLogicNotDeleteValue());
    }

    @Test
    void objectTablesShouldCarryStorageSourceIdColumn() {
        assertEquals("storage_source_id", columnOf(WbFileObject.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(WbPipelineProduct.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(WbChunkSet.class, "storageSourceId"));
        assertEquals("storage_source_id", columnOf(WbEmbeddingSet.class, "storageSourceId"));
        assertEquals("avatar_storage_source_id", columnOf(WbUser.class, "avatarStorageSourceId"));
    }

    @Test
    void objectTablesShouldKeepStorageTypeColumn() {
        assertEquals("storage_type", columnOf(WbFileObject.class, "storageType"));
        assertEquals("storage_type", columnOf(WbPipelineProduct.class, "storageType"));
        assertEquals("storage_type", columnOf(WbChunkSet.class, "storageType"));
        assertEquals("storage_type", columnOf(WbEmbeddingSet.class, "storageType"));
        assertEquals("wb_file_object", TableInfoHelper.getTableInfo(WbFileObject.class).getTableName());
        assertEquals("wb_user", TableInfoHelper.getTableInfo(WbUser.class).getTableName());
    }
}
