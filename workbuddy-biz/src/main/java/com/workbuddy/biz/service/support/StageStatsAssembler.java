package com.workbuddy.biz.service.support;

import com.workbuddy.common.dto.response.chunk.ChunkStatsVO;
import com.workbuddy.common.dto.response.embed.EmbedStatsVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import com.workbuddy.common.dto.response.structure.StructureStatsVO;
import com.workbuddy.common.dto.response.task.StageStatsVO;
import com.workbuddy.common.utils.NullUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环节统计 VO 组装器：把支撑类算出的统计 map 转成对应环节的具体 VO。
 *
 * <p>只做字段搬运，不计算、不改数；统计口径留在各环节的 StatsSupport。
 */
public final class StageStatsAssembler {

    /** 向量化记录数 */
    private static final String KEY_RECORD_COUNT = "recordCount";

    /** 向量化缓存命中数 */
    private static final String KEY_CACHED_COUNT = "cachedCount";

    /** step_log 聚合的匹配数 */
    private static final String KEY_MATCHED = "matched";

    /** step_log 聚合的改动数 */
    private static final String KEY_CHANGED = "changed";

    private StageStatsAssembler() {
    }

    /**
     * 按环节分派到具体统计 VO。
     *
     * @param stage 环节（PipelineStage 枚举名）
     * @param stats 统计 map；为 null 时返回 null
     * @return 具体环节统计 VO；解析或未知环节返回空基类
     */
    public static StageStatsVO from(String stage, Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        if (NullUtil.isNull(stage)) {
            return new StageStatsVO();
        }
        return switch (stage) {
            case "CHUNK" -> fromChunk(stats);
            case "PREPROCESS" -> fromPreprocess(stats);
            case "STRUCTURE" -> fromStructure(stats);
            case "EMBED" -> fromEmbed(stats);
            default -> new StageStatsVO();
        };
    }

    /**
     * 切片统计 map → 切片 VO。
     *
     * @param stats 统计 map；为 null 时返回 null
     * @return 切片统计 VO
     */
    public static ChunkStatsVO fromChunk(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        ChunkStatsVO vo = new ChunkStatsVO();
        vo.setChunkCount(intOf(stats.get(ChunkStatsSupport.KEY_CHUNK_COUNT)));
        vo.setSectionCount(intOf(stats.get(ChunkStatsSupport.KEY_SECTION_COUNT)));
        vo.setParagraphCount(intOf(stats.get(ChunkStatsSupport.KEY_PARAGRAPH_COUNT)));
        vo.setTableCount(intOf(stats.get(ChunkStatsSupport.KEY_TABLE_COUNT)));
        vo.setImageCount(intOf(stats.get(ChunkStatsSupport.KEY_IMAGE_COUNT)));
        vo.setFallbackCount(intOf(stats.get(ChunkStatsSupport.KEY_FALLBACK_COUNT)));
        vo.setParentCount(intOf(stats.get(ChunkStatsSupport.KEY_PARENT_COUNT)));
        vo.setOrphanCount(intOf(stats.get(ChunkStatsSupport.KEY_ORPHAN_COUNT)));
        vo.setTotalChars(intOf(stats.get(ChunkStatsSupport.KEY_TOTAL_CHARS)));
        vo.setAvgChars(intOf(stats.get(ChunkStatsSupport.KEY_AVG_CHARS)));
        vo.setMaxChars(intOf(stats.get(ChunkStatsSupport.KEY_MAX_CHARS)));
        vo.setOverTargetMaxCount(intOf(stats.get(ChunkStatsSupport.KEY_OVER_TARGET_MAX_COUNT)));
        vo.setOverSoftMaxCount(intOf(stats.get(ChunkStatsSupport.KEY_OVER_SOFT_MAX_COUNT)));
        vo.setMinMergeLen(intOf(stats.get(ChunkStatsSupport.KEY_MIN_MERGE_LEN)));
        vo.setUnderMinMergeCount(intOf(stats.get(ChunkStatsSupport.KEY_UNDER_MIN_MERGE_COUNT)));
        vo.setRoutedElementCount(intOf(stats.get(ChunkStatsSupport.KEY_ROUTED_ELEMENT_COUNT)));
        vo.setSkippedElementCount(intOf(stats.get(ChunkStatsSupport.KEY_SKIPPED_ELEMENT_COUNT)));
        vo.setMultiSourceChunkCount(intOf(stats.get(ChunkStatsSupport.KEY_MULTI_SOURCE_CHUNK_COUNT)));
        vo.setAvgSourceElementCount(intOf(stats.get(ChunkStatsSupport.KEY_AVG_SOURCE_ELEMENT_COUNT)));
        vo.setTargetMaxLen(intOf(stats.get(ChunkStatsSupport.KEY_TARGET_MAX_LEN)));
        vo.setSoftMaxLen(intOf(stats.get(ChunkStatsSupport.KEY_SOFT_MAX_LEN)));
        vo.setFallbackLen(intOf(stats.get(ChunkStatsSupport.KEY_FALLBACK_LEN)));
        vo.setFallbackOverlap(intOf(stats.get(ChunkStatsSupport.KEY_FALLBACK_OVERLAP)));
        vo.setDurationMs(longOf(stats.get(ChunkStatsSupport.KEY_DURATION_MS)));
        return vo;
    }

    /**
     * 预处理统计 map → 预处理 VO。
     *
     * @param stats 统计 map；为 null 时返回 null
     * @return 预处理统计 VO
     */
    public static PreprocessStatsVO fromPreprocess(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        PreprocessStatsVO vo = new PreprocessStatsVO();
        vo.setMatched(longOf(stats.get(KEY_MATCHED)));
        vo.setChanged(longOf(stats.get(KEY_CHANGED)));
        vo.setElementCount(intOf(stats.get(PreprocessStatsSupport.KEY_ELEMENT_COUNT)));
        vo.setRetainedCount(intOf(stats.get(PreprocessStatsSupport.KEY_RETAINED_COUNT)));
        vo.setRetentionPercent(intOf(stats.get(PreprocessStatsSupport.KEY_RETENTION_PERCENT)));
        vo.setExcludedCount(intOf(stats.get(PreprocessStatsSupport.KEY_EXCLUDED_COUNT)));
        vo.setRepeatedCount(intOf(stats.get(PreprocessStatsSupport.KEY_REPEATED_COUNT)));
        vo.setChunkSkippedCount(intOf(stats.get(PreprocessStatsSupport.KEY_CHUNK_SKIPPED_COUNT)));
        vo.setMarkedCount(intOf(stats.get(PreprocessStatsSupport.KEY_MARKED_COUNT)));
        vo.setFieldCount(intOf(stats.get(PreprocessStatsSupport.KEY_FIELD_COUNT)));
        vo.setChangedCount(intOf(stats.get(PreprocessStatsSupport.KEY_CHANGED_COUNT)));
        vo.setEncodingCount(intOf(stats.get(PreprocessStatsSupport.KEY_ENCODING_COUNT)));
        vo.setTidyCount(intOf(stats.get(PreprocessStatsSupport.KEY_TIDY_COUNT)));
        vo.setStatusCounts(countsOf(stats.get(PreprocessStatsSupport.KEY_STATUS_COUNTS)));
        vo.setFieldTypeCounts(countsOf(stats.get(PreprocessStatsSupport.KEY_FIELD_TYPE_COUNTS)));
        vo.setPageFrom(intOf(stats.get(PreprocessStatsSupport.KEY_PAGE_FROM)));
        vo.setPageTo(intOf(stats.get(PreprocessStatsSupport.KEY_PAGE_TO)));
        vo.setDurationMs(longOf(stats.get(PreprocessStatsSupport.KEY_DURATION_MS)));
        return vo;
    }

    /**
     * 组装统计 map → 组装 VO。
     *
     * @param stats 统计 map；为 null 时返回 null
     * @return 组装统计 VO
     */
    public static StructureStatsVO fromStructure(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        StructureStatsVO vo = new StructureStatsVO();
        vo.setElementCount(intOf(stats.get(StructureStatsSupport.KEY_ELEMENT_COUNT)));
        vo.setTitleCount(intOf(stats.get(StructureStatsSupport.KEY_TITLE_COUNT)));
        vo.setChapterCount(intOf(stats.get(StructureStatsSupport.KEY_CHAPTER_COUNT)));
        vo.setLevel1Count(intOf(stats.get(StructureStatsSupport.KEY_LEVEL_1)));
        vo.setLevel2Count(intOf(stats.get(StructureStatsSupport.KEY_LEVEL_2)));
        vo.setLevel3PlusCount(intOf(stats.get(StructureStatsSupport.KEY_LEVEL_3_PLUS)));
        vo.setParentChildCount(intOf(stats.get(StructureStatsSupport.KEY_PARENT_CHILD_COUNT)));
        vo.setContinuationCount(intOf(stats.get(StructureStatsSupport.KEY_CONTINUATION_COUNT)));
        vo.setSuspectedContinuationCount(
                intOf(stats.get(StructureStatsSupport.KEY_SUSPECTED_CONTINUATION_COUNT)));
        vo.setTracedCount(intOf(stats.get(StructureStatsSupport.KEY_TRACED_COUNT)));
        vo.setProvenanceCoverage(intOf(stats.get(StructureStatsSupport.KEY_PROVENANCE_COVERAGE)));
        vo.setWithoutBboxCount(intOf(stats.get(StructureStatsSupport.KEY_WITHOUT_BBOX_COUNT)));
        vo.setRepeatedSegmentCount(
                intOf(stats.get(StructureStatsSupport.KEY_REPEATED_SEGMENT_COUNT)));
        vo.setRepeatedPageCount(intOf(stats.get(StructureStatsSupport.KEY_REPEATED_PAGE_COUNT)));
        vo.setNoisePageCount(intOf(stats.get(StructureStatsSupport.KEY_NOISE_PAGE_COUNT)));
        vo.setPageCount(intOf(stats.get(StructureStatsSupport.KEY_PAGE_COUNT)));
        vo.setContinuationFromPage(
                intOf(stats.get(StructureStatsSupport.KEY_CONTINUATION_FROM_PAGE)));
        vo.setContinuationToPage(
                intOf(stats.get(StructureStatsSupport.KEY_CONTINUATION_TO_PAGE)));
        vo.setSchemaVersion(stringOf(stats.get(StructureStatsSupport.KEY_SCHEMA_VERSION)));
        vo.setDurationMs(longOf(stats.get(StructureStatsSupport.KEY_DURATION_MS)));
        return vo;
    }

    /**
     * 向量化统计 map → 向量化 VO。
     *
     * @param stats 统计 map；为 null 时返回 null
     * @return 向量化统计 VO
     */
    public static EmbedStatsVO fromEmbed(Map<String, Object> stats) {
        if (NullUtil.isNull(stats)) {
            return null;
        }
        EmbedStatsVO vo = new EmbedStatsVO();
        vo.setRecordCount(intOf(stats.get(KEY_RECORD_COUNT)));
        vo.setCachedCount(intOf(stats.get(KEY_CACHED_COUNT)));
        return vo;
    }

    /** 数字 → Integer；非数字返回 null。 */
    private static Integer intOf(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /** 数字 → Long；非数字返回 null。 */
    private static Long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 字符串原样返回；非字符串返回 null。 */
    private static String stringOf(Object value) {
        return value instanceof String text ? text : null;
    }

    /** 分布 map → Map<String,Integer>；非 map 返回 null，空 map 保留为空。 */
    private static Map<String, Integer> countsOf(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
                counts.put(key, number.intValue());
            }
        }
        return counts;
    }
}
