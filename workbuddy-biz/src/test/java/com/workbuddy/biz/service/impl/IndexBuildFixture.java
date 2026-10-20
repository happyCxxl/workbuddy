package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.IndexPublishService;
import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbIndexSetDbService;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbPipelineTaskDbService;
import com.workbuddy.biz.service.support.index.IndexBuildSupport;
import com.workbuddy.biz.service.support.index.IndexComboReconciler;
import com.workbuddy.biz.service.support.index.IndexLineageResolver;
import com.workbuddy.biz.service.support.assemble.IndexRowAssembler;
import com.workbuddy.biz.service.support.index.IndexVersionStateSupport;
import com.workbuddy.biz.task.support.TaskQueueSupport;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import com.workbuddy.worker.indexing.MilvusIndexPort;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 索引构建单测夹具：真实构建服务 + 真实状态/构建支撑，数据访问、外部端口按系统边界替换为 mock。
 */
final class IndexBuildFixture {

    final WbKnowledgeBaseDbService knowledgeBaseDbService = mock(WbKnowledgeBaseDbService.class);
    final WbIndexSetDbService indexSetDbService = mock(WbIndexSetDbService.class);
    final WbIndexVersionDbService indexVersionDbService = mock(WbIndexVersionDbService.class);
    final WbPipelineTaskDbService pipelineTaskDbService = mock(WbPipelineTaskDbService.class);
    final WbFileResultDbService fileResultDbService = mock(WbFileResultDbService.class);
    final WbChunkSetDbService chunkSetDbService = mock(WbChunkSetDbService.class);
    final WbEmbeddingSetDbService embeddingSetDbService = mock(WbEmbeddingSetDbService.class);
    final WbPipelineStrategyVersionDbService strategyVersionDbService =
            mock(WbPipelineStrategyVersionDbService.class);
    final IndexComboService indexComboService = mock(IndexComboService.class);
    final ChunkControlService chunkControlService = mock(ChunkControlService.class);
    final PreprocessControlService preprocessControlService = mock(PreprocessControlService.class);
    final WbPipelineProductDbService productDbService = mock(WbPipelineProductDbService.class);
    final IndexLineageResolver lineageResolver = mock(IndexLineageResolver.class);
    final IndexRowAssembler indexRowAssembler = mock(IndexRowAssembler.class);
    final IndexComboReconciler indexComboReconciler = mock(IndexComboReconciler.class);
    final MilvusIndexPort milvusIndexPort = mock(MilvusIndexPort.class);
    final TaskQueueSupport taskQueue = mock(TaskQueueSupport.class);
    final IndexPublishService publishService = mock(IndexPublishService.class);

    final IndexVersionStateSupport stateSupport =
            new IndexVersionStateSupport(indexSetDbService, indexVersionDbService);
    final IndexBuildSupport buildSupport = new IndexBuildSupport(stateSupport, indexComboReconciler,
            milvusIndexPort, indexSetDbService, indexVersionDbService, chunkSetDbService,
            strategyVersionDbService, preprocessControlService, chunkControlService, productDbService,
            lineageResolver);
    final IndexBuildServiceImpl service = new IndexBuildServiceImpl(knowledgeBaseDbService, indexSetDbService,
            indexVersionDbService, pipelineTaskDbService, fileResultDbService, chunkSetDbService,
            embeddingSetDbService, indexComboService, lineageResolver, indexRowAssembler, milvusIndexPort,
            taskQueue, publishService, stateSupport, buildSupport);

    void stubProductCombo(WbFileResult file, String preprocess, String chunk, String embed) {
        WbEmbeddingSet embedRow = new WbEmbeddingSet();
        embedRow.setId(2L);
        embedRow.setFileResultId(file.getId());
        embedRow.setChunkSetRef(1L);
        embedRow.setStrategyVersion(embed);
        embedRow.setDimension(1024);
        when(embeddingSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(embedRow));
        WbChunkSet chunkRow = new WbChunkSet();
        chunkRow.setId(1L);
        chunkRow.setFileResultId(file.getId());
        chunkRow.setChunkStrategyVersion(chunk);
        chunkRow.setUpstreamProductId(100L);
        when(chunkSetDbService.getById(1L)).thenReturn(chunkRow);
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn(preprocess);
        when(indexRowAssembler.assemble(eq(file.getId()), eq("userA"), eq(chunkRow), eq(embedRow)))
                .thenReturn(List.of(IndexSetTestFixtures.indexRow()));
    }

    void stubCompleteCombo(WbFileResult file) {
        when(fileResultDbService.listByKb(IndexSetTestFixtures.KB_ID)).thenReturn(List.of(file));
        WbChunkSet chunkSet = IndexSetTestFixtures.chunkRowOf(1L, file.getId(), "chunk-hybrid-v1", 100L);
        when(chunkSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(chunkSet));
        WbEmbeddingSet embedSet = new WbEmbeddingSet();
        embedSet.setId(2L);
        embedSet.setFileResultId(file.getId());
        embedSet.setStrategyVersion("embed-default-v1");
        when(embeddingSetDbService.listByFileResultIds(List.of(file.getId()))).thenReturn(List.of(embedSet));
        when(lineageResolver.resolvePreprocessStrategy(100L)).thenReturn("preproc-default-v1");
    }

    void stubSavedVersion(Long versionId) {
        when(indexVersionDbService.save(any(WbIndexVersion.class))).thenAnswer(inv -> {
            inv.<WbIndexVersion>getArgument(0).setId(versionId);
            return true;
        });
    }

    void stubTaskSave(Long taskId) {
        when(pipelineTaskDbService.save(any(WbPipelineTask.class))).thenAnswer(inv -> {
            inv.<WbPipelineTask>getArgument(0).setId(taskId);
            return true;
        });
    }

    void stubAppendTarget() {
        when(indexSetDbService.getOrCreateByKb(IndexSetTestFixtures.KB_ID))
                .thenReturn(IndexSetTestFixtures.indexSet());
        when(indexVersionDbService.listByIndexSetId(IndexSetTestFixtures.SET_ID)).thenReturn(List.of());
        when(indexVersionDbService.nextVersionNo(IndexSetTestFixtures.SET_ID)).thenReturn("v1");
        stubSavedVersion(100L);
    }

    void stubOnlineCombo(String versionNo, ComboSnapshot combo) {
        WbIndexVersion online = new WbIndexVersion();
        online.setId(50L);
        online.setIndexSetId(IndexSetTestFixtures.SET_ID);
        online.setVersionNo(versionNo);
        online.setStatus("ONLINE");
        online.setComboSnapshot(JsonUtil.toJsonStr(combo));
        when(publishService.currentPublished(IndexSetTestFixtures.KB_ID)).thenReturn(online);
    }
}
