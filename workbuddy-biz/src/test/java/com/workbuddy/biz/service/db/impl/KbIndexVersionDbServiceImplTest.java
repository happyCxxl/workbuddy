package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.workbuddy.biz.mapper.KbIndexVersionMapper;
import com.workbuddy.common.domain.entity.KbIndexVersion;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引版本数据访问服务单测：列表排序 / 版本号生成。
 *
 * @author cxxl
 */
class KbIndexVersionDbServiceImplTest {

    private KbIndexVersionMapper mapper;

    private KbIndexVersionDbServiceImpl impl;

    @BeforeAll
    static void initLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                KbIndexVersion.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(KbIndexVersionMapper.class);
        impl = new KbIndexVersionDbServiceImpl();
        ReflectionTestUtils.setField(impl, "baseMapper", mapper);
    }

    private KbIndexVersion version(Long id) {
        KbIndexVersion v = new KbIndexVersion();
        v.setId(id);
        v.setStatus("READY");
        return v;
    }

    @Test
    void listByIndexSetIdShouldOrderByIdDesc() {
        when(mapper.selectList(any())).thenReturn(List.of(version(2L), version(1L)));

        List<KbIndexVersion> result = impl.listByIndexSetId(10L);

        assertEquals(2, result.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbIndexVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        assertTrue(captor.getValue().getSqlSegment().contains("ORDER BY id DESC"), captor.getValue().getSqlSegment());
    }

    @Test
    void nextVersionNoShouldReturnMaxSuffixPlusOne() {
        // 回收会物理删行：行数+1 在删行后会重号，必须按现有最大数字后缀 +1
        when(mapper.selectObjs(any())).thenReturn(List.of(4L));

        assertEquals("v5", impl.nextVersionNo(10L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KbIndexVersion>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectObjs(captor.capture());
        assertTrue(captor.getValue().getSqlSelect().contains("MAX(CAST(SUBSTRING(version_no, 2) AS UNSIGNED))"),
                captor.getValue().getSqlSelect());
    }

    @Test
    void nextVersionNoShouldReturnV1WhenNoRows() {
        when(mapper.selectObjs(any())).thenReturn(List.of(0L));

        assertEquals("v1", impl.nextVersionNo(10L));
    }
}
