package com.workbuddy.biz.service.support;

import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.service.FileStorage;
import com.workbuddy.worker.indexing.IndexRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 索引行装配器：单文件装配 IndexRow——向量产物 SUCCESS/CACHED 记录子集；content=record.inputText；
 * titlePath/sourceElementIds 自切片产物按 chunkId 关联。追加路径与批量构建共用。
 *
 * @author cxxl
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRowAssembler {

    private final FileStorage fileStorage;

    /**
     * 装配单文件索引行。
     *
     * @return 产物读取失败 → null；无 SUCCESS/CACHED 记录 → 空列表
     */
    public List<IndexRow> assemble(Long fileResultId, String owner, WbChunkSet chunkRow, WbEmbeddingSet embedRow) {
        EmbeddingSet embedSet = readEmbeddingSet(ObjectRef.ofEmbeddingSet(embedRow));
        if (NullUtil.isNull(embedSet) || NullUtil.isNull(embedSet.getRecords())) {
            log.warn("===> IndexRowAssembler 向量产物缺失或记录为空, artifactId={}", embedRow.getArtifactId());
            return null;
        }
        Map<String, Chunk> chunkById = readChunkIndex(ObjectRef.ofChunkSet(chunkRow));
        List<IndexRow> rows = new ArrayList<>();
        for (EmbeddingRecord record : embedSet.getRecords()) {
            if (!"SUCCESS".equals(record.getStatus()) && !"CACHED".equals(record.getStatus())) {
                continue;
            }
            Chunk chunk = NullUtil.isNull(chunkById) ? null : chunkById.get(record.getChunkId());
            IndexRow row = new IndexRow();
            row.setChunkId(record.getChunkId());
            row.setDocumentId(fileResultId);
            row.setOwner(owner);
            row.setContentType(record.getContentType());
            row.setParentChunkId(record.getParentChunkId());
            row.setContent(record.getInputText());
            row.setTitlePath(NullUtil.isNull(chunk) ? null : chunk.getTitlePath());
            row.setSourceElementIds(NullUtil.isNull(chunk) || NullUtil.isNull(chunk.getSourceElementIds())
                    ? null : JsonUtil.toJsonStr(chunk.getSourceElementIds()));
            row.setVector(record.getVector());
            rows.add(row);
        }
        return rows;
    }

    /** 向量产物位置 → EmbeddingSet（读取失败返回 null） */
    public EmbeddingSet readEmbeddingSet(ObjectRef ref) {
        try {
            byte[] content = fileStorage.getObject(ref);
            if (NullUtil.isNull(content)) {
                return null;
            }
            return JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), EmbeddingSet.class);
        } catch (Exception e) {
            log.warn("向量产物读取失败, object={}", ref.path(), e);
            return null;
        }
    }

    /** 切片产物位置 → chunkId 索引（读取失败返回 null，增强字段降级为空） */
    public Map<String, Chunk> readChunkIndex(ObjectRef ref) {
        try {
            byte[] content = fileStorage.getObject(ref);
            if (NullUtil.isNull(content)) {
                return null;
            }
            ChunkSet chunkSet = JsonUtil.toObject(new String(content, StandardCharsets.UTF_8), ChunkSet.class);
            if (NullUtil.isNull(chunkSet) || NullUtil.isNull(chunkSet.getChunks())) {
                return null;
            }
            Map<String, Chunk> index = new HashMap<>();
            for (Chunk chunk : chunkSet.getChunks()) {
                index.put(chunk.getChunkId(), chunk);
            }
            return index;
        } catch (Exception e) {
            log.warn("切片产物读取失败, object={}", ref.path(), e);
            return null;
        }
    }
}
