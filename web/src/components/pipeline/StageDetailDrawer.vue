<template>
  <Teleport to="body">
    <div v-if="visible" class="parse-drawer-mask" @click="onClose">
      <aside class="parse-drawer" role="dialog" aria-modal="true" @click.stop>
        <!-- 头部：文件 + 本次运行标识 + 状态徽标 -->
        <header class="parse-drawer-head">
          <div class="parse-drawer-title">
            <span class="parse-drawer-file">{{ fileName }}</span>
            <span class="parse-drawer-badge" :class="badgeClass">{{ statusText }}</span>
          </div>
          <button class="parse-drawer-x" type="button" @click="onClose">关闭</button>
        </header>
        <div class="parse-drawer-ident">
          <span class="parse-drawer-ident-item">开始 {{ formatClock(detail?.startedAt) }}</span>
          <span class="parse-drawer-ident-item"
            >耗时 {{ formatDuration(stats?.durationMs ?? detailDurationMs) }}</span
          >
          <span class="parse-drawer-ident-item">任务 {{ taskId }}</span>
        </div>

        <!-- 统计条：固定，一行摘要 + 一行统计 -->
        <div class="parse-drawer-statbar">
          <span class="parse-drawer-conclusion" :class="conclusionClass">{{ conclusionText }}</span>
          <div class="parse-drawer-stats">
            <span v-for="item in statItems" :key="item.key" class="parse-drawer-stat">
              <span class="parse-drawer-stat-value">{{ item.value }}</span>
              {{ item.label }}
            </span>
          </div>
        </div>

        <!-- 失败态：固定，解析失败的运行一眼可见 -->
        <section
          v-if="isFailed"
          class="parse-drawer-block parse-drawer-block-bad parse-drawer-failbar"
        >
          <span class="parse-drawer-error-code">{{ detail?.errorCode ?? '—' }}</span>
          <span class="parse-drawer-error-msg">{{
            detail?.errorMsg ?? '本次运行没有留下失败说明'
          }}</span>
          <span class="parse-drawer-advice">建议：{{ failAdvice }}</span>
        </section>

        <div ref="splitBodyRef" class="parse-drawer-body" :class="{ 'is-dragging': dragging }">
          <!-- 左栏：内容形态由环节配置给（原文预览 / 组装产物文档 / 清洗后的正文）；宽度由分隔条决定 -->
          <section class="parse-drawer-pane parse-drawer-pane-source" :style="sourcePaneStyle">
            <div class="parse-drawer-pane-head">
              <h4 class="parse-drawer-block-title">{{ leftPaneTitle }}</h4>
              <span class="parse-drawer-hint">
                <template v-if="leftPaneKind === 'assembly'">
                  <span v-if="docAllBlocks.length > 0"
                    >共 {{ docAllBlocks.length }} 个元素 · {{ docTitleCount }} 个标题</span
                  >
                  <span v-if="docBlocks.length > docRenderLimit">
                    · 已渲染 {{ docRenderLimit }}</span
                  >
                </template>
                <template v-else-if="leftPaneKind === 'cleaned'">
                  <span v-if="docAllBlocks.length > 0"
                    >共 {{ cleanedKeptCount }} 个保留元素<span v-if="docDroppedCount > 0">
                      · 已剔除 {{ docDroppedCount }}</span
                    ></span
                  >
                  <span v-if="docBlocks.length > docRenderLimit">
                    · 已渲染 {{ docRenderLimit }}</span
                  >
                </template>
                <template v-else-if="leftPaneKind === 'chunks'">
                  <span v-if="readingChunks.length > 0"
                    >共 {{ readingChunks.length }} 片 · 按阅读序<span v-if="chunkMode !== 'all'">
                      · 当前档 {{ visibleChunks.length }}</span
                    ></span
                  >
                  <span v-if="docBlocks.length > docRenderLimit">
                    · 已渲染 {{ docRenderLimit }}</span
                  >
                </template>
                <template v-else-if="leftPaneKind === 'source'">
                  {{ fileKindText }}<span v-if="fileSizeText"> · {{ fileSizeText }}</span>
                  <span v-if="previewTotal > 0"> · 共 {{ previewTotal }} 页</span>
                  <span v-if="canPreview && highlightCount > 0">
                    · 可定位 {{ highlightCount }} 个元素</span
                  >
                </template>
              </span>
              <!-- 清洗后的正文：只看保留 / 看被剔除（被剔除的块灰化划线留在原位） -->
              <div v-if="leftPaneKind === 'cleaned'" class="parse-drawer-seg">
                <button
                  class="parse-drawer-seg-btn"
                  :class="{ 'is-on': cleanedMode === 'kept' }"
                  type="button"
                  @click="cleanedMode = 'kept'"
                >
                  只看保留
                </button>
                <button
                  class="parse-drawer-seg-btn"
                  :class="{ 'is-on': cleanedMode === 'dropped' }"
                  type="button"
                  @click="cleanedMode = 'dropped'"
                >
                  看被剔除
                </button>
              </div>
              <!-- 切片结果：全部 / 兜底片 / 父片（只影响左栏，右栏各自的页签另有过滤） -->
              <div v-else-if="leftPaneKind === 'chunks'" class="parse-drawer-seg">
                <button
                  class="parse-drawer-seg-btn"
                  :class="{ 'is-on': chunkMode === 'all' }"
                  type="button"
                  @click="chunkMode = 'all'"
                >
                  全部
                </button>
                <button
                  class="parse-drawer-seg-btn"
                  :class="{ 'is-on': chunkMode === 'fallback' }"
                  type="button"
                  @click="chunkMode = 'fallback'"
                >
                  兜底片
                </button>
                <button
                  class="parse-drawer-seg-btn"
                  :class="{ 'is-on': chunkMode === 'parent' }"
                  type="button"
                  @click="chunkMode = 'parent'"
                >
                  父片
                </button>
              </div>
            </div>
            <div class="parse-drawer-pane-fill">
              <!-- 文档形态左栏（组装产物文档 / 清洗后的正文）：按元素类型排版；
                   块与右栏行都以产物元素 id 对齐，两侧互相定位 -->
              <template v-if="isDocPane">
                <p v-if="detailLoading" class="parse-drawer-hint">{{ docLoadingText }}</p>
                <p v-else-if="detailError" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ detailError }}
                </p>
                <p v-else-if="docBlocks.length === 0" class="parse-drawer-hint">
                  {{ docEmptyText }}
                </p>
                <div v-else ref="docRef" class="parse-drawer-doc" @scroll="onDocScroll">
                  <div
                    v-for="block in renderedDocBlocks"
                    :key="block.key"
                    class="parse-drawer-doc-block"
                    :class="[
                      block.className,
                      { 'is-picked': block.key === pickedKey, 'is-dropped': block.dropped },
                    ]"
                    :data-key="block.key"
                    @click="onDocPick(block.key)"
                  >
                    <span v-if="block.seqText" class="parse-drawer-doc-seq">{{
                      block.seqText
                    }}</span>
                    <span v-if="block.badge" class="parse-drawer-doc-badge">{{ block.badge }}</span>
                    <span v-if="block.stateText" class="parse-drawer-doc-state">{{
                      block.stateText
                    }}</span>
                    <span v-if="block.meta" class="parse-drawer-doc-meta">{{ block.meta }}</span>
                    <table
                      v-if="block.kind === 'table' && block.grid.length > 0"
                      class="parse-drawer-doc-table"
                    >
                      <tbody>
                        <tr
                          v-for="(row, rowIndex) in block.grid"
                          :key="rowIndex"
                          class="parse-drawer-doc-tr"
                        >
                          <td
                            v-for="(cell, colIndex) in row"
                            :key="colIndex"
                            class="parse-drawer-doc-td"
                            :class="{ 'is-head': cell !== null && cell.isHeader }"
                          >
                            {{ cell?.text ?? '' }}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                    <span v-else-if="block.kind === 'figure'" class="parse-drawer-doc-image">{{
                      block.text
                    }}</span>
                    <span v-else class="parse-drawer-doc-text">{{ block.text }}</span>
                  </div>
                  <p v-if="docRenderLimit < docBlocks.length" class="parse-drawer-hint">
                    继续向下滚动加载剩余 {{ docBlocks.length - docRenderLimit }} 个元素
                  </p>
                </div>
              </template>
              <template v-else-if="leftPaneKind === 'source'">
                <p v-if="sourceError" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ sourceError }}
                </p>
                <p v-else-if="!sourceBlob && previewKind !== 'none'" class="parse-drawer-hint">
                  原文件加载中…
                </p>
                <!-- 没有浏览器端渲染器的类型（.doc/.xls/图片等）：只给文件信息与下载 -->
                <p v-else-if="previewKind === 'none'" class="parse-drawer-hint">
                  该类型暂不支持内嵌预览（{{ fileKindText }}），可用底部「下载原文件」查看
                </p>
                <!-- 拿到字节后一律交给对应预览组件：解析失败由它自己报原因（不在这里摘掉组件，
                     否则页码与错误明细都会跟着消失） -->
                <PdfSourcePreview
                  v-else-if="previewKind === 'pdf'"
                  ref="previewRef"
                  :blob="sourceBlob"
                  :highlights="highlights"
                  :active-key="activeKey"
                  @page-change="onPreviewPage"
                  @pick="onPreviewPick"
                  @loaded="onPreviewLoaded"
                />
                <DocxSourcePreview
                  v-else-if="previewKind === 'docx'"
                  ref="docxRef"
                  :blob="sourceBlob"
                  :anchors="docxAnchors"
                  :active-key="activeKey"
                  @pick="onPreviewPick"
                  @loaded="onOfficeLoaded"
                />
                <XlsxSourcePreview
                  v-else-if="previewKind === 'xlsx'"
                  ref="xlsxRef"
                  :blob="sourceBlob"
                  :anchors="xlsxAnchors"
                  :active-key="activeKey"
                  @pick="onPreviewPick"
                  @loaded="onOfficeLoaded"
                />
              </template>
              <!-- 没有左栏形态的环节：不给原文预览，也不去取原文件字节 -->
              <template v-else>
                <p class="parse-drawer-hint">
                  该环节没有可展示的产物内容，看右栏列表与「过程」页签
                </p>
              </template>
            </div>
          </section>

          <!-- 分隔条：拖动调两栏宽度；方向键微调，双击回默认比例 -->
          <div
            class="parse-drawer-splitter"
            :class="{ 'is-dragging': dragging }"
            role="separator"
            aria-orientation="vertical"
            aria-label="调整原文预览与解析结果宽度"
            :aria-valuenow="Math.round(splitRatio * 100)"
            aria-valuemin="20"
            aria-valuemax="90"
            tabindex="0"
            :title="splitterTitle"
            @pointerdown="onSplitterDown"
            @pointermove="onSplitterMove"
            @pointerup="onSplitterUp"
            @pointercancel="onSplitterUp"
            @dblclick="resetSplit"
            @keydown="onSplitterKeydown"
          ></div>

          <!-- 右栏：解析结果。页签固定在本区顶，页签内容各自滚动 -->
          <div ref="resultPaneRef" class="parse-drawer-pane parse-drawer-pane-result">
            <div class="parse-drawer-tabs" role="tablist">
              <button
                v-for="tab in tabs"
                :key="tab.key"
                class="parse-drawer-tab"
                :class="{ 'is-on': activeTab === tab.key }"
                type="button"
                role="tab"
                :aria-selected="activeTab === tab.key"
                @click="activeTab = tab.key"
              >
                {{ tab.label }}
                <span v-if="tabCount(tab.key) > 0" class="parse-drawer-tab-num">{{
                  tabCount(tab.key)
                }}</span>
              </button>
            </div>

            <p v-if="detailLoading" class="parse-drawer-hint">加载中…</p>
            <p v-else-if="detailError" class="parse-drawer-hint parse-drawer-hint-bad">
              {{ detailError }}
            </p>

            <!-- 页签：元素 -->
            <div v-else-if="activeTab === 'elements'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabhead">
                <input
                  v-model="keyword"
                  class="parse-drawer-search"
                  type="search"
                  placeholder="按文本 / 类型过滤"
                />
                <label class="parse-drawer-check">
                  <input v-model="followPreview" type="checkbox" />
                  只列预览当前页
                </label>
                <span v-if="keyword.trim()" class="parse-drawer-hint">只过滤当前页</span>
                <span v-if="itemsLoading" class="parse-drawer-hint">加载中…</span>
                <span v-else-if="itemsError" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ itemsError }}
                </span>
                <span v-else-if="pageMatched !== null" class="parse-drawer-hint"
                  >本页命中 {{ pageMatched }}</span
                >
                <span v-else class="parse-drawer-hint"
                  >本页 {{ rawItems.length }} 条 · 全量 {{ fullElementTotal }} 条</span
                >
              </div>

              <div class="parse-drawer-tabscroll">
                <p v-if="!itemsLoading && elementTotal === 0" class="parse-drawer-hint">
                  {{ followPreview && previewReady ? '该页没有解析元素' : '本次运行没有产物内容' }}
                </p>
                <p v-else-if="visibleItems.length === 0" class="parse-drawer-hint">
                  没有匹配的元素
                </p>

                <ul v-else class="parse-drawer-elements">
                  <li
                    v-for="item in visibleItems"
                    :key="item.key"
                    class="parse-drawer-element"
                    :class="{
                      'is-active': item.key === activeKey,
                      'is-picked': item.key === pickedKey,
                    }"
                    :title="item.provenance || undefined"
                    :data-key="item.key"
                    @click="onElementPick(item.key)"
                  >
                    <div class="parse-drawer-element-head">
                      <span class="parse-drawer-type">{{ item.typeText }}</span>
                      <span class="parse-drawer-element-meta">#{{ item.seq }}</span>
                      <span v-if="item.page !== null" class="parse-drawer-element-meta"
                        >第 {{ item.page }} 页</span
                      >
                      <span v-if="item.source" class="parse-drawer-element-meta">{{
                        item.source
                      }}</span>
                      <span v-if="item.font" class="parse-drawer-element-meta">{{
                        item.font
                      }}</span>
                      <span v-if="item.tocCandidate" class="parse-drawer-element-meta">目录</span>
                      <span
                        v-if="item.degraded"
                        class="parse-drawer-element-meta is-warn"
                        :title="item.degradeReason"
                        >降级</span
                      >
                      <span v-if="item.grid" class="parse-drawer-element-meta">{{
                        item.grid
                      }}</span>
                      <button
                        v-if="item.text"
                        class="parse-drawer-toggle"
                        type="button"
                        @click.stop="toggleExpand(item.key)"
                      >
                        {{ expanded.has(item.key) ? '收起' : '展开' }}
                      </button>
                    </div>
                    <pre
                      v-if="item.text"
                      class="parse-drawer-element-text"
                      :class="{ 'is-expanded': expanded.has(item.key) }"
                      :title="item.text"
                      >{{ item.text }}</pre>
                    <span v-else-if="item.tableGrid.length === 0" class="parse-drawer-element-meta"
                      >（无文本）</span
                    >
                    <table v-if="item.tableGrid.length > 0" class="parse-drawer-element-grid">
                      <tbody>
                        <tr v-for="(row, rowIndex) in item.tableGrid" :key="rowIndex">
                          <td
                            v-for="(cell, colIndex) in row"
                            :key="colIndex"
                            class="parse-drawer-element-cell"
                            :class="{ 'is-head': cell !== null && cell.isHeader }"
                          >
                            {{ cell?.text ?? '' }}
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </li>
                </ul>
              </div>

              <div class="parse-drawer-tabfoot">
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="page <= 1 || itemsLoading"
                  @click="goPage(page - 1)"
                >
                  上一页
                </button>
                <span class="parse-drawer-page-info">第 {{ page }} / {{ pageCount }} 页</span>
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="page >= pageCount || itemsLoading"
                  @click="goPage(page + 1)"
                >
                  下一页
                </button>
                <span class="parse-drawer-page-info"
                  >共 {{ elementTotal }} 条 · 每页 {{ PAGE_SIZE }}</span
                >
              </div>
            </div>

            <!-- 页签：结构树（组装环节；与元素页签同一份分页内容，按标题层级缩进） -->
            <div v-else-if="activeTab === 'tree'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <p v-if="itemsError" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ itemsError }}
                </p>
                <p v-else-if="!itemsLoading && elementTotal === 0" class="parse-drawer-hint">
                  本次运行没有产物内容
                </p>
                <p v-else-if="treeRows.length === 0" class="parse-drawer-hint">
                  {{ itemsLoading ? '结构树加载中…' : '本页没有可展示的结构行' }}
                </p>
                <ul v-else class="parse-drawer-tree">
                  <li
                    v-for="row in treeRows"
                    :key="row.key"
                    class="parse-drawer-tree-row"
                    :class="{ 'is-on': row.key === activeKey, 'is-backup': row.isBackup }"
                    :style="{ paddingLeft: row.indent }"
                    :data-key="row.key"
                    @click="onTreePick(row.key)"
                  >
                    <div class="parse-drawer-tree-head">
                      <span class="parse-drawer-type">{{ row.typeText }}</span>
                      <span class="parse-drawer-element-meta">#{{ row.seq }}</span>
                      <span v-if="row.page !== null" class="parse-drawer-element-meta"
                        >第 {{ row.page }} 页</span
                      >
                      <span v-if="row.grid" class="parse-drawer-element-meta">{{ row.grid }}</span>
                      <button
                        v-if="row.expandable"
                        class="parse-drawer-toggle"
                        type="button"
                        @click.stop="toggleExpand(row.key)"
                      >
                        {{ expanded.has(row.key) ? '收起' : '展开' }}
                      </button>
                    </div>
                    <pre
                      v-if="row.text"
                      class="parse-drawer-tree-text"
                      :class="{ 'is-expanded': expanded.has(row.key) }"
                      :title="row.text"
                      >{{ row.text }}</pre>
                    <span v-else class="parse-drawer-element-meta">（无文本）</span>
                  </li>
                </ul>
              </div>

              <div class="parse-drawer-tabfoot">
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="page <= 1 || itemsLoading"
                  @click="goPage(page - 1)"
                >
                  上一页
                </button>
                <span class="parse-drawer-page-info">第 {{ page }} / {{ pageCount }} 页</span>
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="page >= pageCount || itemsLoading"
                  @click="goPage(page + 1)"
                >
                  下一页
                </button>
                <span class="parse-drawer-page-info"
                  >共 {{ elementTotal }} 条 · 每页 {{ PAGE_SIZE }}</span
                >
              </div>
            </div>

            <!-- 页签：剔除内容（预处理；只列不进切片的元素，按状态过滤后分页） -->
            <div v-else-if="activeTab === 'excluded'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <p v-if="excludedError" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ excludedError }}
                </p>
                <p
                  v-else-if="excludedLoading && excludedRows.length === 0"
                  class="parse-drawer-hint"
                >
                  剔除内容加载中…
                </p>
                <p v-else-if="excludedRows.length === 0" class="parse-drawer-hint">
                  本次运行没有剔除内容（全部进入切片）
                </p>
                <ul v-else class="parse-drawer-elements">
                  <li
                    v-for="row in excludedRows"
                    :key="row.key"
                    class="parse-drawer-element"
                    :class="{ 'is-active': row.key === activeKey }"
                    :data-key="row.key"
                    @click="onElementPick(row.key)"
                  >
                    <div class="parse-drawer-element-head">
                      <span class="parse-drawer-type">{{ row.typeText }}</span>
                      <span class="parse-drawer-state">{{ row.stateText }}</span>
                      <span v-if="row.page !== null" class="parse-drawer-element-meta"
                        >第 {{ row.page }} 页</span
                      >
                    </div>
                    <p class="parse-drawer-excluded-text">{{ row.text || '（无文本）' }}</p>
                    <p v-if="row.reason" class="parse-drawer-hint">依据：{{ row.reason }}</p>
                  </li>
                </ul>
              </div>

              <div class="parse-drawer-tabfoot">
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="excludedPage <= 1 || excludedLoading"
                  @click="goExcludedPage(excludedPage - 1)"
                >
                  上一页
                </button>
                <span class="parse-drawer-page-info"
                  >第 {{ excludedPage }} / {{ excludedPageCount }} 页</span
                >
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="excludedPage >= excludedPageCount || excludedLoading"
                  @click="goExcludedPage(excludedPage + 1)"
                >
                  下一页
                </button>
                <span class="parse-drawer-page-info"
                  >共 {{ excludedTotal }} 条 · 每页 {{ PAGE_SIZE }}</span
                >
              </div>
            </div>

            <!-- 页签：字段（预处理；标准化字段一览） -->
            <div v-else-if="activeTab === 'fields'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <p v-if="fieldRows.length === 0" class="parse-drawer-hint">
                  本次运行没有标准化字段
                </p>
                <ul v-else class="parse-drawer-fields">
                  <li v-for="row in fieldRows" :key="row.key" class="parse-drawer-field">
                    <span class="parse-drawer-type">{{ row.fieldText }}</span>
                    <span class="parse-drawer-field-value">{{ row.valueText }}</span>
                    <span class="parse-drawer-element-meta">{{ row.typeText }}</span>
                    <span v-if="row.page !== null" class="parse-drawer-element-meta"
                      >第 {{ row.page }} 页</span
                    >
                    <span v-if="row.rule" class="parse-drawer-element-meta">{{ row.rule }}</span>
                  </li>
                </ul>
              </div>
            </div>

            <!-- 页签：兜底片 / 父片 · 孤儿（切片；服务端按片类型与父子关系过滤后分页） -->
            <div
              v-else-if="activeTab === 'fallback' || activeTab === 'parents'"
              class="parse-drawer-tabpane"
            >
              <div class="parse-drawer-tabscroll">
                <p v-if="activeChunkPane.error" class="parse-drawer-hint parse-drawer-hint-bad">
                  {{ activeChunkPane.error }}
                </p>
                <p
                  v-else-if="activeChunkPane.loading && activeChunkRows.length === 0"
                  class="parse-drawer-hint"
                >
                  {{ activeChunkLoadingText }}
                </p>
                <p v-else-if="activeChunkRows.length === 0" class="parse-drawer-hint">
                  {{ activeChunkEmptyText }}
                </p>
                <ul v-else class="parse-drawer-elements">
                  <li
                    v-for="row in activeChunkRows"
                    :key="row.key"
                    class="parse-drawer-element"
                    :class="{ 'is-active': row.key === activeKey }"
                    :data-key="row.key"
                    @click="onElementPick(row.key)"
                  >
                    <div class="parse-drawer-element-head">
                      <span v-if="row.seqText" class="parse-drawer-chunk-seq">{{
                        row.seqText
                      }}</span>
                      <span class="parse-drawer-type">{{ row.typeText }}</span>
                      <span v-if="row.meta" class="parse-drawer-element-meta">{{ row.meta }}</span>
                    </div>
                    <p class="parse-drawer-excluded-text">{{ row.text || '（无内容）' }}</p>
                  </li>
                </ul>
              </div>

              <div class="parse-drawer-tabfoot">
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="activeChunkPane.page <= 1 || activeChunkPane.loading"
                  @click="goChunkPanePage(activeChunkPane.page - 1)"
                >
                  上一页
                </button>
                <span class="parse-drawer-page-info"
                  >第 {{ activeChunkPane.page }} / {{ activeChunkPanePages }} 页</span
                >
                <button
                  class="parse-drawer-page-btn"
                  type="button"
                  :disabled="
                    activeChunkPane.page >= activeChunkPanePages || activeChunkPane.loading
                  "
                  @click="goChunkPanePage(activeChunkPane.page + 1)"
                >
                  下一页
                </button>
                <span class="parse-drawer-page-info"
                  >共 {{ activeChunkPane.total }} 条 · 每页 {{ PAGE_SIZE }}</span
                >
              </div>
            </div>

            <!-- 页签：来源对照（切片；按左栏选中的片给来源元素个数与关联字段） -->
            <div v-else-if="activeTab === 'sources'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <p v-if="selectedChunk === null" class="parse-drawer-hint">
                  在左栏点一片、或在「切片」页签点一行，这里给它的来源对照
                </p>
                <template v-else>
                  <h5 class="parse-drawer-subtitle">
                    {{ selectedChunk.chunkId ?? '—' }} · {{ chunkBadgeOf(selectedChunk) }}
                  </h5>
                  <ul class="parse-drawer-fields">
                    <li v-for="row in sourceRows" :key="row.label" class="parse-drawer-field">
                      <span class="parse-drawer-element-meta">{{ row.label }}</span>
                      <span class="parse-drawer-field-value">{{ row.value }}</span>
                    </li>
                  </ul>
                  <p class="parse-drawer-hint">
                    来源元素只给个数：父片的溯源是全部子片的并集（条数随章节大小无界），逐个来源元素走产物或索引里的溯源
                  </p>
                </template>
              </div>
            </div>

            <!-- 页签：告警（结构化告警优先；旧响应退回纯文案） -->
            <div v-else-if="activeTab === 'warnings'" class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <h5 v-if="warningItems.length > 0" class="parse-drawer-subtitle">
                  告警 {{ warningItems.length }}
                </h5>
                <ul v-if="warningItems.length > 0" class="parse-drawer-warnings">
                  <li
                    v-for="(warn, index) in warningItems"
                    :key="`${warn.code}-${index}`"
                    class="parse-drawer-warning"
                  >
                    <span class="parse-drawer-warning-code">{{ warn.level }} {{ warn.code }}</span>
                    <span>{{ warn.message }}</span>
                    <button
                      v-if="warn.page && canPreview"
                      class="parse-drawer-page-link"
                      type="button"
                      @click="goDocPage(warn.page)"
                    >
                      第 {{ warn.page }} 页
                    </button>
                  </li>
                </ul>
                <h5
                  v-if="warningItems.length === 0 && warnings.length > 0"
                  class="parse-drawer-subtitle"
                >
                  告警 {{ warnings.length }}
                </h5>
                <ul
                  v-if="warningItems.length === 0 && warnings.length > 0"
                  class="parse-drawer-warnings"
                >
                  <li v-for="(warn, index) in warnings" :key="index" class="parse-drawer-warning">
                    <template v-for="(part, partIndex) in warningParts(warn)" :key="partIndex">
                      <button
                        v-if="part.page"
                        class="parse-drawer-page-link"
                        type="button"
                        @click="goDocPage(part.page)"
                      >
                        {{ part.text }}
                      </button>
                      <span v-else>{{ part.text }}</span>
                    </template>
                  </li>
                </ul>
                <h5 v-if="conflicts.length > 0" class="parse-drawer-subtitle">
                  冲突 {{ conflicts.length }}
                </h5>
                <ul v-if="conflicts.length > 0" class="parse-drawer-warnings">
                  <li v-for="(item, index) in conflicts" :key="index" class="parse-drawer-warning">
                    <span class="parse-drawer-conflict-pair">
                      {{ item.primaryElementId ?? '—' }} / {{ item.backupElementId ?? '—' }}
                    </span>
                    {{ item.message ?? '该处两路内容不一致，已按主路保留' }}
                  </li>
                </ul>
                <p
                  v-if="
                    warningItems.length === 0 && warnings.length === 0 && conflicts.length === 0
                  "
                  class="parse-drawer-hint"
                >
                  本次运行没有告警
                </p>
              </div>
            </div>

            <!-- 页签：过程（子步骤） -->
            <div v-else class="parse-drawer-tabpane">
              <div class="parse-drawer-tabscroll">
                <p v-if="steps.length === 0" class="parse-drawer-hint">本次运行没有子步骤记录</p>
                <ul v-else class="parse-drawer-steps">
                  <li
                    v-for="step in steps"
                    :key="step.stepName"
                    class="parse-drawer-step"
                    :class="{ 'is-bad': step.status === 'FAILED' }"
                  >
                    <span class="parse-drawer-step-name">{{ step.stepName }}</span>
                    <span class="parse-drawer-step-meta">{{ step.status ?? '—' }}</span>
                    <span class="parse-drawer-step-meta">{{ formatDuration(step.duration) }}</span>
                    <span class="parse-drawer-step-meta">告警 {{ step.warningCount ?? 0 }}</span>
                    <span v-if="step.error" class="parse-drawer-step-error">{{ step.error }}</span>
                  </li>
                </ul>
              </div>
            </div>
          </div>
        </div>

        <!-- 底部操作 -->
        <footer class="parse-drawer-foot">
          <button
            class="parse-drawer-btn"
            type="button"
            :disabled="!canDownload || downloading"
            :title="downloadReason"
            @click="downloadSource"
          >
            {{ downloading ? '下载中…' : '下载原文件' }}
          </button>
          <button class="parse-drawer-btn parse-drawer-btn-primary" type="button" @click="copyAll">
            复制当前页文本
          </button>
          <span v-if="copyHint" class="parse-drawer-hint">{{ copyHint }}</span>
        </footer>
      </aside>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';

import { readSplitRatio, removeSplitRatio, writeSplitRatio } from '@/utils/drawer-split-storage';
import { pushEntityTitle, restoreTitle } from '@/utils/page-title';
import { getStageContent, getStageDetail, getSourceFile } from '@/api/pipeline';
import PdfSourcePreview from '@/components/pipeline/PdfSourcePreview.vue';
import DocxSourcePreview from '@/components/pipeline/DocxSourcePreview.vue';
import XlsxSourcePreview from '@/components/pipeline/XlsxSourcePreview.vue';
import {
  bboxOf,
  chunkTypeLabel,
  elementTypeLabel,
  formatCount,
  formatDuration,
  pageOf,
  stageLabel,
  statNumber,
  statusTone,
  taskStatusLabel,
} from '@/types/pipeline';
import {
  PREPROCESS_CHUNK_SKIP_STATUSES,
  PREPROCESS_FIELD_LABELS,
  PREPROCESS_STATUS_LABELS,
  stageViewOf,
} from '@/types/pipeline-stage-view';
import type { DocxAnchor } from '@/components/pipeline/DocxSourcePreview.vue';
import type { XlsxAnchor } from '@/components/pipeline/XlsxSourcePreview.vue';
import type { PreviewHighlight } from '@/components/pipeline/PdfSourcePreview.vue';
import type { StageTab, StageTabKey } from '@/types/pipeline-stage-view';
import type {
  ChunkDetail,
  ChunkItem,
  ChunkStats,
  ParseDetail,
  ParseWarning,
  PreprocessDetail,
  PreprocessElement,
  PreprocessField,
  StageContentExtra,
  StageContentItem,
  StructureCellItem,
  StructureDetail,
  StructureOutlineItem,
} from '@/types/pipeline';

// 环节详情抽屉：只看不改 —— 不发任何写请求，也不改链图的选中与轮询。
const props = defineProps<{
  visible: boolean;
  /** 当前文件结果 */
  fileResultId: string;
  /** 被查看的那次运行（必填：看的是"这一张卡"，不是"最新一次"） */
  taskId: string;
  /** 被查看的环节（PipelineStage 枚举名）：决定详情接口、页签、统计项与左栏形态 */
  stage: string;
  /** 文件名（头部展示与下载保存名；由页面从文件列表带进来） */
  fileName: string;
  /** 源文件引用（原文件下载用：文件结果里的 fileId；缺失时下载按钮置灰） */
  sourceFileId: string;
}>();

const emit = defineEmits<{ close: [] }>();

/** 当前环节的展示配置：页签、统计项与左栏形态都从这里取 */
const stageView = computed(() => stageViewOf(props.stage));

/** 左栏内容形态 */
const leftPaneKind = computed(() => stageView.value.leftPaneKind);

/** 左栏标题：按形态给（原文预览 / 组装后的文档 / 清洗后的正文 / 切片结果是四件事） */
const leftPaneTitle = computed(() => {
  if (leftPaneKind.value === 'assembly') {
    return '组装后的文档';
  }
  if (leftPaneKind.value === 'cleaned') {
    return '清洗后的正文';
  }
  return leftPaneKind.value === 'chunks' ? '切片结果' : '原文预览';
});

/** 每页条数：与后端上限（1000）留出余量，单次响应可控 */
const PAGE_SIZE = 100;

// ---- 左栏：组装产物文档（组装环节用） ----

/** 文档块一次渲染多少条：大文档不做一次性全渲染，滚动到底再追加 */
const DOC_RENDER_STEP = 80;
/** 距底部多远触发追加渲染 */
const DOC_LOAD_MARGIN = 240;

/** 表格渲染的格数上限：超过只按行列数占位，不做整表渲染 */
const DOC_TABLE_MAX_CELLS = 1200;

/** 标题层级上限：产物层级再深也只按 4 级排版 */
const DOC_TITLE_MAX_LEVEL = 4;

/** 文档块形态：标题 / 分节 / 段落 / 表格 / 图片 / 弱化块（页眉页脚）/ 一般文本 */
type DocBlockKind = 'title' | 'section' | 'paragraph' | 'table' | 'figure' | 'weak' | 'text';

/** 文档块里的一个表格单元格 */
interface DocCell {
  text: string;
  isHeader: boolean;
}

/** 文档块：一个产物元素排成的一段内容 */
interface DocBlock {
  /** 产物元素 id（与右栏行同键，两侧按它互相定位） */
  key: string;
  kind: DocBlockKind;
  /** 类型标签（只给页眉页脚，避免与正文混淆） */
  badge: string;
  /** 序号前缀（切片结果给 `#12`；其余形态空串） */
  seqText: string;
  /** 弱化说明（切片结果给标题路径 / 字数 / 来源元素数；其余形态空串） */
  meta: string;
  /** 状态标签（清洗后的正文给"仅标记"/"剔除·原因"；其余形态空串） */
  stateText: string;
  /** 是否不进切片（预处理：剔除态与重复份；其余形态恒 false） */
  dropped: boolean;
  /** 块的样式类名（标题按层级、分节、弱化块各一档） */
  className: string;
  text: string;
  /** 表格网格（产物无单元格明细时为空数组） */
  grid: (DocCell | null)[][];
}

/** 文档滚动容器（左栏定位用） */
const docRef = ref<HTMLElement | null>(null);

/** 文档已渲染到第几条（滚动递增；定位时按目标块补足） */
const docRenderLimit = ref(DOC_RENDER_STEP);

/** 详情：按环节取到的是各自的结构（解析 / 组装 / 预处理 / 切片），页面按 `in` 判别专属字段 */
const detail = ref<ParseDetail | StructureDetail | PreprocessDetail | ChunkDetail | null>(null);
const detailLoading = ref(false);
const detailError = ref('');

const rawItems = ref<StageContentItem[]>([]);
const elementTotal = ref(0);
/** 全量元素数（不随 docPage/筛选变化；页签角标固定用这份） */
const allElementTotal = ref(0);
/** 全量中可原文定位的元素数 */
const locatableTotal = ref(0);
const page = ref(1);
const itemsLoading = ref(false);
const itemsError = ref('');
/** 元素请求序号：首开全量请求与预览就绪后的按页请求可能并发，只认最后一次 */
let itemsRequestSeq = 0;

const keyword = ref('');
const expanded = ref(new Set<string>());

// ---- 原文预览与双栏联动 ----

/** 原文件字节（预览用）与加载态；与解析详情各自独立取数 */
const sourceBlob = ref<Blob | null>(null);
const sourceError = ref('');
/** 预览组件的联合引用：按当前类型取到的那一个会被赋值 */
const previewRef = ref<InstanceType<typeof PdfSourcePreview> | null>(null);
const docxRef = ref<InstanceType<typeof DocxSourcePreview> | null>(null);
const xlsxRef = ref<InstanceType<typeof XlsxSourcePreview> | null>(null);
/** 右栏滚动容器（点原文后把命中的元素滚到可见） */
const resultPaneRef = ref<HTMLElement | null>(null);
/** 双栏容器（分隔条按它的宽度换算左栏像素宽与最小宽度约束） */
const splitBodyRef = ref<HTMLElement | null>(null);

// ---- 分隔条：两栏宽度 ----

/** 左栏最小宽（再窄原文读不了） */
const SPLIT_MIN_LEFT = 320;
/** 右栏最小宽（再窄元素列表不可用） */
const SPLIT_MIN_RIGHT = 360;
/** 方向键每次调整的像素 */
const SPLIT_KEY_STEP = 24;
/** 默认左栏比例（双击恢复到这个值），与样式里的初始宽度同口径 */
const SPLIT_DEFAULT_RATIO = 0.62;

/**
 * 左栏宽度（px）。**null 表示还没量过容器**，此时不写内联宽度，
 * 让样式里的默认值（62% / min-width 420px）生效，避免测量前闪一下。
 */
const sourceWidth = ref<number | null>(null);
const dragging = ref(false);
/** 拖动起点：指针 x 与当时的左栏宽 */
const dragFrom = ref({ pointerX: 0, width: 0 });

/** 双栏容器当前宽度；量不到时按 0 处理（约束函数会退回最小宽） */
function bodyWidth(): number {
  return splitBodyRef.value?.clientWidth ?? 0;
}

/**
 * 把想要的左栏宽夹进合法区间。
 *
 * <p>两侧最小宽都保证：容器太窄放不下两栏最小宽时，优先保左栏最小宽。
 */
function clampWidth(width: number): number {
  const total = bodyWidth();
  const maxLeft = Math.max(total - SPLIT_MIN_RIGHT, SPLIT_MIN_LEFT);
  return Math.min(Math.max(Math.round(width), SPLIT_MIN_LEFT), maxLeft);
}

/** 左栏占双栏的比例：量不到容器宽度时按默认比例 */
const splitRatio = computed(() => {
  const total = bodyWidth();
  if (total <= 0 || sourceWidth.value === null) {
    return SPLIT_DEFAULT_RATIO;
  }
  return sourceWidth.value / total;
});

const sourcePaneStyle = computed(() =>
  sourceWidth.value === null
    ? undefined
    : { width: `${sourceWidth.value}px`, minWidth: `${SPLIT_MIN_LEFT}px` },
);

const splitterTitle = computed(
  () =>
    `拖动调整两栏宽度；双击恢复默认（左栏 ${Math.round(SPLIT_DEFAULT_RATIO * 100)}%）；←/→ 微调`,
);

/** 按像素设置左栏宽（夹住后写回，并落到偏好里） */
function applyWidth(width: number, persist: boolean): void {
  const next = clampWidth(width);
  sourceWidth.value = next;
  if (persist) {
    writeSplitRatio(props.fileResultId, props.taskId, next / Math.max(bodyWidth(), 1));
  }
}

/** 按默认比例设置左栏宽 */
function applyDefaultWidth(): void {
  const total = bodyWidth();
  sourceWidth.value = clampWidth(total > 0 ? total * SPLIT_DEFAULT_RATIO : SPLIT_MIN_LEFT);
}

/**
 * 拖动开始：记下起点并捕获指针，后续 move/up 都发到分隔条自己身上。
 *
 * <p>`setPointerCapture` 放在 try 里：某些环境（合成事件、指针已被系统接管）
 * 会抛异常，而那不该让整个拖动挂掉 —— 捕获失败时事件仍会冒泡到本元素，
 * 只是指针移出分隔条后可能收不到 move。
 */
function onSplitterDown(event: PointerEvent): void {
  if (sourceWidth.value === null) {
    applyDefaultWidth();
  }
  dragging.value = true;
  dragFrom.value = { pointerX: event.clientX, width: sourceWidth.value ?? SPLIT_MIN_LEFT };
  try {
    (event.currentTarget as HTMLElement | null)?.setPointerCapture(event.pointerId);
  } catch {
    // 捕获失败：拖动仍能工作，只是指针离开分隔条后可能丢事件
  }
  event.preventDefault();
}

function onSplitterMove(event: PointerEvent): void {
  if (!dragging.value) {
    return;
  }
  applyWidth(dragFrom.value.width + (event.clientX - dragFrom.value.pointerX), false);
}

function onSplitterUp(event: PointerEvent): void {
  if (!dragging.value) {
    return;
  }
  dragging.value = false;
  const handle = event.currentTarget as HTMLElement | null;
  if (handle?.hasPointerCapture(event.pointerId)) {
    handle.releasePointerCapture(event.pointerId);
  }
  // 拖动结束才落盘：拖动过程中每帧写 localStorage 没有必要
  if (sourceWidth.value !== null) {
    writeSplitRatio(props.fileResultId, props.taskId, sourceWidth.value / Math.max(bodyWidth(), 1));
  }
}

/** 双击：回到默认比例，并把记录清掉（"默认"与"没记录过"是同一种状态） */
function resetSplit(): void {
  removeSplitRatio(props.fileResultId, props.taskId);
  applyDefaultWidth();
}

/** 方向键：左右各按固定像素调整，Home/End 到两端 */
function onSplitterKeydown(event: KeyboardEvent): void {
  const base = sourceWidth.value ?? SPLIT_MIN_LEFT;
  if (event.key === 'ArrowLeft') {
    applyWidth(base - SPLIT_KEY_STEP, true);
  } else if (event.key === 'ArrowRight') {
    applyWidth(base + SPLIT_KEY_STEP, true);
  } else if (event.key === 'Home') {
    applyWidth(SPLIT_MIN_LEFT, true);
  } else if (event.key === 'End') {
    applyWidth(Number.MAX_SAFE_INTEGER, true);
  } else {
    return;
  }
  event.preventDefault();
}

/**
 * 量一次容器宽度并定下左栏宽。
 *
 * <p>优先用记住的比例，没有记录就按默认比例算 —— 两者都要过最小宽度约束。
 */
function measureSplit(): void {
  const total = bodyWidth();
  if (total <= 0) {
    return;
  }
  const remembered = readSplitRatio(props.fileResultId, props.taskId);
  sourceWidth.value = clampWidth(total * (remembered ?? SPLIT_DEFAULT_RATIO));
}

/** 窗口尺寸变化：左栏宽按新容器重新夹一次（保持像素意图，不做比例漂移） */
function onWindowResize(): void {
  if (dragging.value) {
    return;
  }
  if (sourceWidth.value === null) {
    measureSplit();
    return;
  }
  applyWidth(sourceWidth.value, false);
}
/** 预览当前页（0 = 预览还没就绪） */
const previewPage = ref(0);
const previewReady = computed(() => previewPage.value > 0);
/** 原文件总页数（预览加载完成后由子组件告知） */
const previewTotal = ref(0);
/** 是否只列预览当前页的元素 */
const followPreview = ref(true);
/** 在原文上点中的元素（与"选中"分开：点选保留高亮，选中随交互移动） */
const pickedKey = ref('');
/** 当前重点标注的元素 */
const activeKey = ref('');

/** 文件名后缀（大写下发给判断）；无后缀时为空 */
const fileExt = computed(() => {
  const name = props.fileName;
  const dot = name.lastIndexOf('.');
  return dot < 0 ? '' : name.slice(dot + 1).toUpperCase();
});

const fileKindText = computed(() => (fileExt.value ? `${fileExt.value} 文件` : '该文件'));

const fileSizeText = computed(() => {
  const size = sourceBlob.value?.size ?? 0;
  if (size <= 0) {
    return '';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KB`;
  }
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
});

/**
 * 预览形态：按当前支持的文件类型分派到对应渲染器。
 *
 * <p>白名单（`knowledge.file.enabled-formats`）= PDF/DOC/DOCX/XLS/XLSX；
 * 其中只有 PDF/DOCX/XLSX 有可用的浏览器端渲染器，DOC/XLS 与图片走降级
 * （图片在建档校验期就被拒，不会到这里）。
 */
type PreviewKind = 'pdf' | 'docx' | 'xlsx' | 'none';

const previewKind = computed<PreviewKind>(() => {
  if (fileExt.value === 'PDF') {
    return 'pdf';
  }
  if (fileExt.value === 'DOCX') {
    return 'docx';
  }
  if (fileExt.value === 'XLSX') {
    return 'xlsx';
  }
  return 'none';
});

/** 当前类型是否支持内嵌预览（决定提示文案与是否显示"可定位 N 个元素"） */
const canPreview = computed(() => previewKind.value !== 'none');

/** DOCX 元素 id 形如 `p12` / `t3`，其中数字即段落 / 表格序号（与解析产物同口径） */
function docxAnchorOf(item: StageContentItem, key: string): DocxAnchor | null {
  const id = item.alignKey ?? '';
  const match = /^([pt])(\d+)$/.exec(id);
  if (match === null) {
    return null;
  }
  const isTable = match[1] === 't';
  if (isTable && item.type !== 'TABLE') {
    return null;
  }
  const extra = item.extra ?? {};
  return {
    key,
    type: item.type ?? '',
    paragraphIndex: Number(match[2]),
    style: typeof extra.style === 'string' ? extra.style : '',
  };
}

/** XLSX 锚点：工作表名 + 单元格行列（0 基，与表格区域高亮同口径） */
function xlsxAnchorOf(item: StageContentItem, key: string): XlsxAnchor | null {
  const extra = item.extra ?? {};
  const sheetName = typeof extra.sheetName === 'string' ? extra.sheetName : '';
  const row = statNumber(extra.row);
  const col = statNumber(extra.col);
  // 表格元素只有工作表名、没有行列：用它定位整表
  if (sheetName !== '' && row === null && col === null) {
    return { key, type: item.type ?? '', sheetName, row: 0, col: 0, rowSpan: 0, colSpan: 0 };
  }
  if (sheetName === '' || row === null || col === null) {
    return null;
  }
  return {
    key,
    type: item.type ?? '',
    sheetName,
    row,
    col,
    rowSpan: statNumber(extra.rowSpan) ?? 1,
    colSpan: statNumber(extra.colSpan) ?? 1,
  };
}

/** Word 可定位锚点：只取能在渲染结果里对上号的元素 */
const docxAnchors = computed<DocxAnchor[]>(() =>
  rawItems.value
    .map((item, index) => docxAnchorOf(item, item.alignKey ?? `seq-${item.seq ?? index}`))
    .filter((anchor): anchor is DocxAnchor => anchor !== null),
);

/** Excel 可定位锚点 */
const xlsxAnchors = computed<XlsxAnchor[]>(() =>
  rawItems.value
    .map((item, index) => xlsxAnchorOf(item, item.alignKey ?? `seq-${item.seq ?? index}`))
    .filter((anchor): anchor is XlsxAnchor => anchor !== null),
);

/** 列表行的类型标签：切片用片类型名（父片 / 兜底），其余环节用元素类型名 */
function rowTypeLabel(item: StageContentItem): string {
  return props.stage === 'CHUNK' ? chunkTypeLabel(item.type) : elementTypeLabel(item.type);
}

/** 列表行的来源列：切片给标题路径，其余环节给产物来源 */
function rowSourceText(extra: StageContentExtra | null | undefined): string {
  const value = props.stage === 'CHUNK' ? extra?.titlePath : extra?.source;
  return typeof value === 'string' ? value : '';
}

/** 元素行：把 extra 里的页码/来源/网格/坐标摊平成可渲染字段，并按关键字过滤当前页 */
const visibleItems = computed(() => {
  const text = keyword.value.trim().toLowerCase();
  return rawItems.value
    .map((item, index) => {
      const extra = item.extra ?? {};
      const rows = statNumber(extra.rows);
      const cols = statNumber(extra.cols);
      return {
        key: item.alignKey ?? `seq-${item.seq ?? index}`,
        seq: item.seq ?? index + 1,
        typeText: rowTypeLabel(item),
        rawType: item.type ?? '',
        text: item.display ?? '',
        page: pageOf(item.extra),
        source: rowSourceText(item.extra),
        font: typeof extra.font === 'string' ? extra.font : '',
        tocCandidate: extra.tocCandidate === true,
        provenance: typeof extra.provenance === 'string' ? extra.provenance : '',
        degraded: extra.degraded === true,
        degradeReason: typeof extra.degradeReason === 'string' ? extra.degradeReason : '',
        grid: rows !== null && cols !== null ? `${rows}×${cols}` : '',
        tableGrid: elementTableGrid(item.extra, rows, cols),
      };
    })
    .filter(
      (item) =>
        text === '' ||
        item.text.toLowerCase().includes(text) ||
        item.rawType.toLowerCase().includes(text) ||
        item.typeText.includes(text),
    );
});

/** 高亮框：只取有坐标且有页码的元素（Office 元素无坐标，自然不画） */
const highlights = computed<PreviewHighlight[]>(() =>
  rawItems.value
    .map((item, index) => {
      const bbox = bboxOf(item.extra);
      const page = pageOf(item.extra);
      if (!bbox || page === null) {
        return null;
      }
      const key = item.alignKey ?? `seq-${item.seq ?? index}`;
      const excerpt = (item.display ?? '').replace(/\s+/g, ' ').slice(0, 40);
      return {
        key,
        page,
        bbox,
        title: `${elementTypeLabel(item.type)}${excerpt ? `：${excerpt}` : ''}`,
      };
    })
    .filter((box): box is PreviewHighlight => box !== null),
);

/** 可定位元素数：全量口径由后端下发；旧响应缺字段时退回当前页高亮数 */
const highlightCount = computed(() => locatableTotal.value || highlights.value.length);

/**
 * 环节统计（判解析/组装用）：解析有固定的 parseStats 结构，其余环节用通用的 stageStats。
 *
 * <p>耗时口径：解析读产物统计里的 durationMs（与执行树卡片同一份值）；
 * 组装没有该字段，读通用统计的 durationMs，再不行才按任务起止时间现算。
 */
const stats = computed(() => {
  const value = detail.value;
  return value !== null && 'parseStats' in value ? value.parseStats : null;
});

/** 元素全量角标口径：接口全量优先，旧响应退回解析统计里的元素数，最后才用过滤后总数 */
const fullElementTotal = computed(
  () => allElementTotal.value || statNumber(stats.value?.elementCount) || elementTotal.value,
);

const warnings = computed<string[]>(() => {
  const value = detail.value;
  return value !== null && 'warnings' in value ? (value.warnings ?? []) : [];
});
/** 结构化告警：后端新字段；旧响应为空时告警页退回纯文案渲染 */
const warningItems = computed<ParseWarning[]>(() => {
  const value = detail.value;
  return value !== null && 'warningItems' in value ? (value.warningItems ?? []) : [];
});
const steps = computed(() => detail.value?.steps ?? []);

/** 组装冲突（仅组装详情有该字段；解析环节恒空） */
const conflicts = computed(() => {
  const value = detail.value;
  return value !== null && 'conflicts' in value ? (value.conflicts ?? []) : [];
});

/** 文档块的归一入参：组装大纲 / 预处理视图元素 / 切片都映射到这一份字段 */
interface DocSource {
  /** 产物元素 id（与右栏行同键） */
  key: string;
  type: string;
  text: string;
  level: number | null;
  rows: number | null;
  cols: number | null;
  caption: string | null;
  cells: StructureCellItem[];
  /** 是否不进切片（预处理：剔除态与重复份；其余形态恒 false） */
  dropped: boolean;
  /** 状态标签（预处理：仅标记 / 剔除·原因；其余形态空串） */
  stateText: string;
  /** 类型标签（切片按片类型给；其余形态空串，由类型推导） */
  badge: string;
  /** 序号前缀（切片给 `#12`；其余形态空串） */
  seqText: string;
  /** 弱化说明（切片给标题路径 / 字数 / 来源元素数；其余形态空串） */
  meta: string;
  /** 附加样式类（切片按片类型给边框色；其余形态空串） */
  extraClass: string;
}

/** 组装产物大纲（仅组装详情有 outline；其余环节恒空）：左栏文档按它排版 */
const outlineItems = computed<StructureOutlineItem[]>(() => {
  const value = detail.value;
  return value !== null && 'outline' in value ? (value.outline ?? []) : [];
});

/** 预处理视图元素（仅预处理详情有 elements；其余环节恒空）：清洗后的正文按它排版 */
const viewElements = computed<PreprocessElement[]>(() => {
  const value = detail.value;
  return value !== null && 'elements' in value ? (value.elements ?? []) : [];
});

/** 组装大纲元素 → 文档块入参 */
function outlineSourceOf(item: StructureOutlineItem, index: number): DocSource {
  return {
    key: item.elementId ?? `outline-${index}`,
    type: item.type ?? '',
    text: (item.text ?? '').trim(),
    level: item.level,
    rows: item.rows,
    cols: item.cols,
    caption: item.caption,
    cells: item.cells ?? [],
    dropped: false,
    stateText: '',
    badge: '',
    seqText: '',
    meta: '',
    extraClass: '',
  };
}

/** 预处理视图元素 → 文档块入参：正文取展示文本（缺展示文本回落原文） */
function cleanedSourceOf(item: PreprocessElement, index: number): DocSource {
  const status = item.status ?? '';
  const dropped = PREPROCESS_CHUNK_SKIP_STATUSES.includes(status);
  const text = (item.displayText ?? '').trim();
  return {
    key: item.elementId ?? `cleaned-${index}`,
    type: item.type ?? '',
    text: text === '' ? (item.rawText ?? '').trim() : text,
    // 视图产物不带标题层级：清洗后的正文标题按同一档呈现，层级读正文里的编号
    level: 1,
    rows: null,
    cols: null,
    caption: null,
    cells: item.cells ?? [],
    dropped,
    stateText: PREPROCESS_STATUS_LABELS[status] ?? '',
    badge: '',
    seqText: '',
    meta: '',
    extraClass: '',
  };
}

/** 左栏文档块的数据源：组装取大纲、清洗取视图元素、切片取按阅读序重排后的片 */
const docSources = computed<DocSource[]>(() => {
  if (leftPaneKind.value === 'cleaned') {
    return viewElements.value.map((item, index) => cleanedSourceOf(item, index));
  }
  if (leftPaneKind.value === 'chunks') {
    return visibleChunks.value.map((item, index) => chunkSourceOf(item, index));
  }
  return outlineItems.value.map((item, index) => outlineSourceOf(item, index));
});

/** 全部文档块（未按"只看保留/看被剔除"过滤） */
const docAllBlocks = computed<DocBlock[]>(() =>
  docSources.value.map((source) => docBlockOf(source)),
);

/** 当前档位下要渲染的文档块：清洗后的正文在"只看保留"档滤掉不进切片的元素 */
const docBlocks = computed<DocBlock[]>(() =>
  leftPaneKind.value === 'cleaned' && cleanedMode.value === 'kept'
    ? docAllBlocks.value.filter((block) => !block.dropped)
    : docAllBlocks.value,
);

/** 文档里的标题数（栏头展示口径） */
const docTitleCount = computed(
  () => docAllBlocks.value.filter((block) => block.kind === 'title').length,
);

/** 不进切片的块数（清洗后的正文栏头展示口径） */
const docDroppedCount = computed(() => docAllBlocks.value.filter((block) => block.dropped).length);

/** 已渲染的文档块：滚动到哪渲染到哪 */
const renderedDocBlocks = computed(() => docBlocks.value.slice(0, docRenderLimit.value));

/** 产物元素 → 文档块：排版口径来自类型，序号 / 说明 / 标签由入参给（切片用） */
function docBlockOf(item: DocSource): DocBlock {
  const body = docBodyOf(item);
  return {
    key: item.key,
    kind: body.kind,
    badge: item.badge === '' ? body.badge : item.badge,
    seqText: item.seqText,
    meta: item.meta,
    stateText: item.stateText,
    dropped: item.dropped,
    className: item.extraClass === '' ? body.className : `${body.className} ${item.extraClass}`,
    text: body.text,
    grid: body.grid,
  };
}

/** 文档块的主体（按元素类型分派排版口径） */
function docBodyOf(item: DocSource): {
  kind: DocBlockKind;
  badge: string;
  className: string;
  text: string;
  grid: (DocCell | null)[][];
} {
  const type = item.type;
  const text = item.text;
  if (type === 'TITLE') {
    const level = Math.min(Math.max(item.level ?? 1, 1), DOC_TITLE_MAX_LEVEL);
    return {
      kind: 'title',
      badge: '',
      className: `parse-drawer-doc-title parse-drawer-doc-title-${level}`,
      text: text === '' ? '（无标题文本）' : text,
      grid: [],
    };
  }
  if (type === 'SECTION') {
    return {
      kind: 'section',
      badge: '',
      className: 'parse-drawer-doc-section',
      text: text === '' ? '（无分节名）' : text,
      grid: [],
    };
  }
  if (type === 'TABLE') {
    return {
      kind: 'table',
      badge: '',
      className: 'parse-drawer-doc-table-wrap',
      text: tablePlaceholderText(item),
      grid: tableGridOf(item),
    };
  }
  if (type === 'IMAGE') {
    return {
      kind: 'figure',
      badge: '',
      className: 'parse-drawer-doc-figure',
      text: text === '' ? (item.caption ?? '图片（产物未含图片内容）') : text,
      grid: [],
    };
  }
  // 页眉页脚必须出现，但弱化并标出类型，避免与正文混淆
  if (type === 'HEADER' || type === 'FOOTER') {
    return {
      kind: 'weak',
      badge: elementTypeLabel(type),
      className: 'parse-drawer-doc-weak',
      text: text === '' ? '（无文本）' : text,
      grid: [],
    };
  }
  return {
    kind: type === 'PARAGRAPH' ? 'paragraph' : 'text',
    badge: '',
    className: type === 'PARAGRAPH' ? 'parse-drawer-doc-paragraph' : 'parse-drawer-doc-text',
    text: text === '' ? '（无文本）' : text,
    grid: [],
  };
}

/** 表格网格：按单元格行列把明细摆进网格（无明细或超上限时不给网格） */
function tableGridOf(item: DocSource): (DocCell | null)[][] {
  const cells = item.cells;
  if (cells.length === 0) {
    return [];
  }
  const rows = Math.max(item.rows ?? 0, maxCellIndex(cells, (cell) => cell.row) + 1);
  const cols = Math.max(item.cols ?? 0, maxCellIndex(cells, (cell) => cell.col) + 1);
  if (rows <= 0 || cols <= 0 || rows * cols > DOC_TABLE_MAX_CELLS) {
    return [];
  }
  const grid: (DocCell | null)[][] = Array.from({ length: rows }, () =>
    Array.from({ length: cols }, () => null),
  );
  for (const cell of cells) {
    const row = cell.row ?? 0;
    const col = cell.col ?? 0;
    if (row < 0 || col < 0 || row >= rows || col >= cols) {
      continue;
    }
    grid[row][col] = { text: cell.text ?? '', isHeader: cell.isHeader === true };
  }
  return grid;
}

/** 单元格行列的最大值（行列缺失按 0 计） */
function maxCellIndex(
  cells: StructureCellItem[],
  pick: (cell: StructureCellItem) => number | null,
): number {
  return cells.reduce((max, cell) => Math.max(max, pick(cell) ?? 0), 0);
}

/**
 * 解析元素行的表格网格：单元格明细来自 extra.cells（JSON 串，后端按产物原样下发）。
 * **不设格数上限**：解析详情要如实展示表格内容，大表也整表渲染。
 */
function elementTableGrid(
  extra: StageContentExtra | null | undefined,
  rows: number | null,
  cols: number | null,
): (DocCell | null)[][] {
  const raw = extra?.cells;
  if (typeof raw !== 'string' || raw === '') {
    return [];
  }
  let cells: StructureCellItem[];
  try {
    cells = JSON.parse(raw) as StructureCellItem[];
  } catch {
    return [];
  }
  if (!Array.isArray(cells) || cells.length === 0) {
    return [];
  }
  const rowCount = Math.max(rows ?? 0, maxCellIndex(cells, (cell) => cell.row) + 1);
  const colCount = Math.max(cols ?? 0, maxCellIndex(cells, (cell) => cell.col) + 1);
  if (rowCount <= 0 || colCount <= 0) {
    return [];
  }
  const grid: (DocCell | null)[][] = Array.from({ length: rowCount }, () =>
    Array.from({ length: colCount }, () => null),
  );
  for (const cell of cells) {
    const row = cell.row ?? 0;
    const col = cell.col ?? 0;
    if (row < 0 || col < 0 || row >= rowCount || col >= colCount) {
      continue;
    }
    grid[row][col] = { text: cell.text ?? '', isHeader: cell.isHeader === true };
  }
  return grid;
}

/** 表格占位文案：无单元格明细时给行列数与说明（行列数缺失时只给说明） */
function tablePlaceholderText(item: DocSource): string {
  if (item.rows === null && item.cols === null) {
    return '表格（产物未含单元格明细）';
  }
  const rows = item.rows === null ? '—' : String(item.rows);
  const cols = item.cols === null ? '—' : String(item.cols);
  return `表格 ${rows} 行 × ${cols} 列（产物未含单元格明细）`;
}

// ---- 左栏：切片结果（切片环节用）/ 右栏：兜底片、父片·孤儿与来源对照 ----

/** 切片列表（仅切片详情有 chunks；其余环节恒空） */
const chunkItems = computed<ChunkItem[]>(() => {
  const value = detail.value;
  return value !== null && 'chunks' in value ? (value.chunks ?? []) : [];
});

/** 片 ID → 片（左栏块键与右栏行键都是 chunkId） */
const chunkByKey = computed(() => {
  const map = new Map<string, ChunkItem>();
  for (const chunk of chunkItems.value) {
    if (chunk.chunkId) {
      map.set(chunk.chunkId, chunk);
    }
  }
  return map;
});

/** 父片 ID → 子片数（父片的说明列要报"128 个子片"） */
const childCountByParent = computed(() => {
  const counts = new Map<string, number>();
  for (const chunk of chunkItems.value) {
    const parentId = chunk.parentChunkId;
    if (parentId) {
      counts.set(parentId, (counts.get(parentId) ?? 0) + 1);
    }
  }
  return counts;
});

/** 是否是被挂子片的父片 */
function isParentChunk(chunk: ChunkItem): boolean {
  return Boolean(chunk.chunkId) && childCountByParent.value.has(chunk.chunkId ?? '');
}

/** 是否是孤儿片（没有父片，本身也不是父片） */
function isOrphanChunk(chunk: ChunkItem): boolean {
  return !chunk.parentChunkId && !isParentChunk(chunk);
}

/**
 * 按阅读序重排：父片锚点 → 其子片 → 孤儿片末尾。
 *
 * <p>产物里的顺序是"父片全部在前、子片随后、孤儿片最后"，属归档序；直接按它排版会让父片堆在文档开头。
 */
const readingChunks = computed<ChunkItem[]>(() => {
  const chunks = chunkItems.value;
  const childrenOf = new Map<string, ChunkItem[]>();
  for (const chunk of chunks) {
    const parentId = chunk.parentChunkId;
    if (!parentId) {
      continue;
    }
    const list = childrenOf.get(parentId) ?? [];
    list.push(chunk);
    childrenOf.set(parentId, list);
  }
  const ordered: ChunkItem[] = [];
  const placed = new Set<string>();
  for (const chunk of chunks) {
    const parentId = chunk.parentChunkId;
    if (!parentId || placed.has(parentId)) {
      continue;
    }
    placed.add(parentId);
    const parent = chunkByKey.value.get(parentId);
    if (parent) {
      ordered.push(parent);
    }
    ordered.push(...(childrenOf.get(parentId) ?? []));
  }
  // 没有子片的父片与孤儿片：末尾按归档序补齐
  for (const chunk of chunks) {
    if (chunk.parentChunkId || (chunk.chunkId && placed.has(chunk.chunkId))) {
      continue;
    }
    ordered.push(chunk);
  }
  return ordered;
});

/** 左栏档位：全部 / 兜底片 / 父片 */
type ChunkMode = 'all' | 'fallback' | 'parent';
const chunkMode = ref<ChunkMode>('all');

/** 左栏实际渲染的片（按档位过滤，只影响左栏） */
const visibleChunks = computed<ChunkItem[]>(() => {
  if (chunkMode.value === 'fallback') {
    return readingChunks.value.filter((chunk) => (chunk.contentType ?? '') === 'FALLBACK');
  }
  if (chunkMode.value === 'parent') {
    return readingChunks.value.filter((chunk) => isParentChunk(chunk));
  }
  return readingChunks.value;
});

/** 切片 → 文档块入参：序号 + 类型标签 + 说明列 */
function chunkSourceOf(chunk: ChunkItem, index: number): DocSource {
  const type = chunk.contentType ?? '';
  const content = (chunk.content ?? '').trim();
  const table = type === 'TABLE' ? parseMarkdownTable(content) : null;
  return {
    key: chunk.chunkId ?? `chunk-${index}`,
    type,
    text: content,
    level: 1,
    rows: table === null ? null : table.rows.length + (table.header === null ? 0 : 1),
    cols: table === null ? null : (table.header ?? table.rows.at(0) ?? []).length,
    caption: null,
    cells: table === null ? [] : tableCellsOf(table),
    dropped: false,
    stateText: '',
    badge: chunkBadgeOf(chunk),
    seqText: `#${chunk.orderNo ?? index + 1}`,
    meta: chunkMetaOf(chunk, table),
    extraClass: chunkBlockClassOf(chunk),
  };
}

/** 片的类型标签：父片 / 孤儿片单列，其余按内容类型 */
function chunkBadgeOf(chunk: ChunkItem): string {
  if (isParentChunk(chunk)) {
    return '父片';
  }
  if (isOrphanChunk(chunk)) {
    return '孤儿片';
  }
  return chunkTypeLabel(chunk.contentType);
}

/** 片的说明列：标题路径 · 字数 · 来源元素数（按片类型补兜底原因 / 子片数 / 表格行列） */
function chunkMetaOf(chunk: ChunkItem, table: MarkdownTable | null): string {
  const parts: string[] = [];
  if (chunk.titlePath) {
    parts.push(chunk.titlePath);
  }
  if (isParentChunk(chunk)) {
    parts.push(`${childCountByParent.value.get(chunk.chunkId ?? '') ?? 0} 个子片`, '不产向量');
  } else if ((chunk.contentType ?? '') === 'FALLBACK') {
    parts.push(chunk.fallbackReason ?? '超长内容按兜底参数递归切分');
  } else if (table !== null) {
    parts.push(
      `表头随片`,
      `${table.rows.length} 行 × ${(table.header ?? table.rows.at(0) ?? []).length} 列`,
    );
  }
  if (chunk.charCount !== null) {
    parts.push(`${formatCount(chunk.charCount)} 字`);
  }
  if (chunk.sourceElementCount !== null) {
    parts.push(`来源 ${chunk.sourceElementCount} 元素`);
  }
  return parts.join(' · ');
}

/** 片的块样式：父片 / 兜底片 / 表格片各一档边框色（其余用默认块样式） */
function chunkBlockClassOf(chunk: ChunkItem): string {
  if (isParentChunk(chunk)) {
    return 'parse-drawer-doc-parent';
  }
  const type = chunk.contentType ?? '';
  if (type === 'FALLBACK') {
    return 'parse-drawer-doc-fallback';
  }
  return type === 'TABLE' ? 'parse-drawer-doc-table-chunk' : '';
}

/** Markdown 表格（表格片 content 的口径） */
interface MarkdownTable {
  header: string[] | null;
  rows: string[][];
}

/**
 * 解析表格片的 Markdown 内容（`| a | b |` + `|---|---|`）。
 *
 * <p>只认管道表格：解析不出表格时返回 null，块按普通文本渲染。
 */
function parseMarkdownTable(content: string): MarkdownTable | null {
  if (content === '' || !content.includes('|')) {
    return null;
  }
  const lines = content
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('|'));
  if (lines.length === 0) {
    return null;
  }
  const cells = lines.map((line) => markdownCellsOf(line));
  const isSeparator = (row: string[]): boolean => row.every((cell) => /^-{2,}$/.test(cell.trim()));
  const header = cells.length > 1 && isSeparator(cells[1]) ? (cells[0] ?? null) : null;
  const rows = cells.filter((row, index) => !isSeparator(row) && (index > 0 || header === null));
  if (rows.length === 0 && header === null) {
    return null;
  }
  return { header, rows };
}

/** Markdown 行 → 单元格文本（管道转义还原，两侧空段去掉） */
function markdownCellsOf(line: string): string[] {
  const trimmed = line.replace(/^\|/, '').replace(/\|$/, '');
  return trimmed.split(/(?<!\\)\|/).map((cell) => cell.replace(/\\\|/g, '|').trim());
}

/** Markdown 表格 → 文档块的单元格（表头行标 isHeader，表格块按它渲染） */
function tableCellsOf(table: MarkdownTable): StructureCellItem[] {
  const cells: StructureCellItem[] = [];
  const push = (row: string[], rowIndex: number, isHeader: boolean): void => {
    row.forEach((text, colIndex) => {
      cells.push({ row: rowIndex, col: colIndex, text, isHeader });
    });
  };
  if (table.header !== null) {
    push(table.header, 0, true);
  }
  table.rows.forEach((row, index) => push(row, index + (table.header === null ? 0 : 1), false));
  return cells;
}

/** 片 ID → 集合内顺序号（换页要用它算，不按左栏阅读序） */
function chunkOrderOf(key: string): number | null {
  const chunk = chunkByKey.value.get(key);
  return chunk?.orderNo ?? null;
}

/** 按过滤条件单独取数的切片页签（兜底片 / 父片 · 孤儿）：过滤与分页都在服务端做 */
interface ChunkPane {
  page: number;
  items: StageContentItem[];
  total: number;
  loading: boolean;
  error: string;
  loaded: boolean;
  load: () => Promise<void>;
  go: (next: number) => void;
  reset: () => void;
}

/** 建一个切片过滤页签的状态（两个页签各自翻页、各自按需取数） */
function createChunkPane(filter: { fallback?: boolean; hasParent?: boolean }): ChunkPane {
  const pane = reactive<ChunkPane>({
    page: 1,
    items: [],
    total: 0,
    loading: false,
    error: '',
    loaded: false,
    load: async () => undefined,
    go: () => undefined,
    reset: () => undefined,
  });
  const pageCount = (): number => Math.max(1, Math.ceil(pane.total / PAGE_SIZE));
  pane.load = async (): Promise<void> => {
    pane.loading = true;
    pane.error = '';
    try {
      const data = await getStageContent(props.fileResultId, props.stage, {
        taskId: props.taskId,
        ...filter,
        current: pane.page,
        size: PAGE_SIZE,
      });
      pane.items = data.items ?? [];
      pane.total = data.total ?? 0;
      pane.loaded = true;
    } catch {
      // 失败提示已由接口层统一拦截处理
      pane.items = [];
      pane.total = 0;
      pane.error = '切片内容加载失败';
    } finally {
      pane.loading = false;
    }
  };
  pane.go = (next: number): void => {
    const target = Math.min(Math.max(next, 1), pageCount());
    if (target === pane.page) {
      return;
    }
    pane.page = target;
    void pane.load();
  };
  pane.reset = (): void => {
    pane.page = 1;
    pane.items = [];
    pane.total = 0;
    pane.error = '';
    pane.loaded = false;
  };
  return pane;
}

/** 兜底片页签（只看 contentType=FALLBACK 的片） */
const fallbackPane = createChunkPane({ fallback: true });

/** 父片 · 孤儿页签（只看没有父片的片：父片与孤儿片都在其中） */
const parentPane = createChunkPane({ hasParent: false });

/** 页签角标数：优先用详情统计（不必先请求列表） */
function chunkStatCount(key: keyof ChunkStats): number | null {
  const value = detail.value;
  if (value === null || !('chunks' in value)) {
    return null;
  }
  const raw = value.stageStats?.[key];
  return typeof raw === 'number' ? raw : null;
}

/** 当前选中的片（左栏点选或右栏点行都会更新；「来源对照」页签按它展示） */
const selectedChunk = computed<ChunkItem | null>(() => {
  const key = pickedKey.value || activeKey.value;
  return key === '' ? null : (chunkByKey.value.get(key) ?? null);
});

/** 选中片的来源对照行 */
const sourceRows = computed(() => {
  const chunk = selectedChunk.value;
  if (chunk === null) {
    return [];
  }
  const rows = [
    { label: '片号', value: chunk.chunkId ?? '—' },
    { label: '类型', value: chunkTypeLabel(chunk.contentType) },
    { label: '标题路径', value: chunk.titlePath ?? '—' },
    {
      label: '字符数',
      value: chunk.charCount === null ? '—' : `${formatCount(chunk.charCount)} 字`,
    },
    {
      label: '来源元素',
      value: chunk.sourceElementCount === null ? '—' : `${chunk.sourceElementCount} 个`,
    },
  ];
  if (isParentChunk(chunk)) {
    rows.push({
      label: '覆盖范围',
      value: `${childCountByParent.value.get(chunk.chunkId ?? '') ?? 0} 个子片（不产向量）`,
    });
  }
  if (chunk.pageRange) {
    rows.push({ label: '页码范围', value: chunk.pageRange });
  }
  if (chunk.tableRef) {
    rows.push({ label: '表格引用', value: chunk.tableRef });
  }
  if (chunk.fallbackReason) {
    rows.push({ label: '兜底原因', value: chunk.fallbackReason });
  }
  return rows;
});

/** 当前激活的切片过滤页签（兜底片与父片·孤儿共用一套排版） */
const activeChunkPane = computed<ChunkPane>(() =>
  activeTab.value === 'parents' ? parentPane : fallbackPane,
);

/** 切片过滤页签的一行 */
interface ChunkRow {
  key: string;
  seqText: string;
  typeText: string;
  meta: string;
  text: string;
}

/** 过滤页签的行：类型标签与说明尽量取详情侧的片（来源元素数只在详情里有） */
function chunkRowsOf(items: StageContentItem[]): ChunkRow[] {
  return items.map((item, index) => {
    const key = itemKeyOf(item, index);
    const chunk = chunkByKey.value.get(key);
    const extra = item.extra ?? {};
    const order = statNumber(extra.orderNo);
    if (chunk === undefined) {
      return {
        key,
        seqText: order === null ? '' : `#${order}`,
        typeText: elementTypeLabel(item.type),
        meta: (item.display ?? '').trim() === '' ? '（无内容）' : '',
        text: (item.display ?? '').trim(),
      };
    }
    return {
      key,
      seqText: `#${chunk.orderNo ?? order ?? index + 1}`,
      typeText: chunkBadgeOf(chunk),
      meta: chunkMetaOf(chunk, null),
      text: (chunk.content ?? item.display ?? '').trim(),
    };
  });
}

/** 过滤页签的行（按当前页签取数） */
const activeChunkRows = computed<ChunkRow[]>(() => chunkRowsOf(activeChunkPane.value.items));

/** 过滤页签的页码总数 */
const activeChunkPanePages = computed(() =>
  Math.max(1, Math.ceil(activeChunkPane.value.total / PAGE_SIZE)),
);

/** 过滤页签的空态文案 */
const activeChunkEmptyText = computed(() =>
  activeTab.value === 'parents'
    ? '本次运行没有父片与孤儿片'
    : '本次运行没有兜底片（未触发超长降级切分）',
);

/** 过滤页签的加载文案 */
const activeChunkLoadingText = computed(() =>
  activeTab.value === 'parents' ? '父片与孤儿片加载中…' : '兜底片加载中…',
);

/** 过滤页签翻页 */
function goChunkPanePage(next: number): void {
  activeChunkPane.value.go(next);
}

// ---- 左栏：清洗后的正文 / 右栏：剔除内容与字段（预处理环节用） ----

/** 清洗后正文的档位：只看保留（默认）/ 看被剔除（被剔除的块灰化划线留在原位） */
type CleanedMode = 'kept' | 'dropped';
const cleanedMode = ref<CleanedMode>('kept');

/** 保留块数（清洗后正文的栏头计数） */
const cleanedKeptCount = computed(() => docAllBlocks.value.length - docDroppedCount.value);

/** 左栏是否是文档形态（组装后的文档、清洗后的正文与切片结果共用一套排版与定位） */
const isDocPane = computed(
  () =>
    leftPaneKind.value === 'assembly' ||
    leftPaneKind.value === 'cleaned' ||
    leftPaneKind.value === 'chunks',
);

/** 文档形态左栏的加载文案 */
const docLoadingText = computed(() => {
  if (leftPaneKind.value === 'cleaned') {
    return '清洗结果加载中…';
  }
  return leftPaneKind.value === 'chunks' ? '切片结果加载中…' : '组装产物加载中…';
});

/** 文档形态左栏的空态文案 */
const docEmptyText = computed(() => {
  if (leftPaneKind.value === 'cleaned') {
    return '本次运行没有可展示的清洗结果';
  }
  return leftPaneKind.value === 'chunks'
    ? '本次运行没有可展示的切片结果'
    : '本次运行没有可展示的组装产物内容';
});

/** 剔除内容：页码与数据（与「清洗结果」各自独立取数） */
const excludedPage = ref(1);
const excludedItems = ref<StageContentItem[]>([]);
const excludedTotal = ref(0);
const excludedLoading = ref(false);
const excludedError = ref('');
/** 是否已取过（切到该页签时按需加载） */
const excludedLoaded = ref(false);

/** 拉「剔除内容」某一页：只取不进切片的元素（剔除态 + 重复份） */
async function loadExcluded(): Promise<void> {
  excludedLoading.value = true;
  excludedError.value = '';
  try {
    const data = await getStageContent(props.fileResultId, props.stage, {
      taskId: props.taskId,
      status: PREPROCESS_CHUNK_SKIP_STATUSES.join(','),
      current: excludedPage.value,
      size: PAGE_SIZE,
    });
    excludedItems.value = data.items ?? [];
    excludedTotal.value = data.total ?? 0;
    excludedLoaded.value = true;
  } catch {
    // 失败提示已由接口层统一拦截处理
    excludedItems.value = [];
    excludedTotal.value = 0;
    excludedError.value = '剔除内容加载失败';
  } finally {
    excludedLoading.value = false;
  }
}

/** 剔除内容行 */
interface ExcludedRow {
  key: string;
  typeText: string;
  /** 状态标签（剔除·原因） */
  stateText: string;
  page: number | null;
  text: string;
  /** 剔除依据（取轨迹里的证据或规则名） */
  reason: string;
}

/** 剔除内容行：状态标签 + 类型 + 页码 + 文本 + 剔除依据 */
const excludedRows = computed<ExcludedRow[]>(() =>
  excludedItems.value.map((item, index) => {
    const extra = item.extra ?? {};
    const status = item.status ?? '';
    const rawText = typeof extra.rawText === 'string' ? extra.rawText.trim() : '';
    return {
      key: itemKeyOf(item, index),
      typeText: elementTypeLabel(item.type),
      stateText: PREPROCESS_STATUS_LABELS[status] ?? status,
      page: pageOf(item.extra),
      text: (item.display ?? '').trim() || rawText,
      reason: traceReason(extra.trace),
    };
  }),
);

/** 轨迹（JSON 串）→ 剔除依据：取第一条的证据，缺失时给规则名 */
function traceReason(raw: unknown): string {
  if (typeof raw !== 'string' || raw === '') {
    return '';
  }
  try {
    const entries = JSON.parse(raw) as { rule?: string | null; evidence?: string | null }[];
    const first = entries.at(0);
    return first?.evidence ?? first?.rule ?? '';
  } catch {
    return '';
  }
}

const excludedPageCount = computed(() => Math.max(1, Math.ceil(excludedTotal.value / PAGE_SIZE)));

/** 剔除内容翻页 */
function goExcludedPage(next: number): void {
  const target = Math.min(Math.max(next, 1), excludedPageCount.value);
  if (target === excludedPage.value) {
    return;
  }
  excludedPage.value = target;
  void loadExcluded();
}

/** 字段行（预处理） */
interface FieldRow {
  key: string;
  typeText: string;
  page: number | null;
  fieldText: string;
  valueText: string;
  rule: string;
}

/** 字段行：视图元素上的标准化字段摊平成一览 */
const fieldRows = computed<FieldRow[]>(() => {
  const rows: FieldRow[] = [];
  viewElements.value.forEach((element, index) => {
    const key = element.elementId ?? `cleaned-${index}`;
    (element.fields ?? []).forEach((field, fieldIndex) => {
      rows.push({
        key: `${key}-${fieldIndex}`,
        typeText: elementTypeLabel(element.type),
        page: element.page,
        fieldText: fieldLabel(field.field),
        valueText: fieldText(field),
        rule: field.rule ?? '',
      });
    });
  });
  return rows;
});

/** 字段类型展示名：未枚举的类型回落原值，缺失给 `—` */
function fieldLabel(field: string | null | undefined): string {
  if (!field) {
    return '—';
  }
  return PREPROCESS_FIELD_LABELS[field] ?? field;
}

/** 字段值文案：带单位时拼上单位 */
function fieldText(field: PreprocessField): string {
  const value = field.value ?? '—';
  return field.unit ? `${value} ${field.unit}` : value;
}

/** 结构树缩进：每层一档，行自身再留一段左内边距 */
const TREE_INDENT_BASE = 10;
const TREE_INDENT_STEP = 14;

/** 结构树行：字段与元素页签的行同口径，另加缩进层级 */
interface TreeRow {
  key: string;
  /** 集合内顺序（渲染行序） */
  seq: number;
  typeText: string;
  text: string;
  /** 文档页码（无页概念的元素为 null） */
  page: number | null;
  /** 行列数文案（表格给 `行×列`） */
  grid: string;
  /** 行左内边距（按标题层级给） */
  indent: string;
  /** 文本是否可展开（没有正文的行只显示占位说明） */
  expandable: boolean;
  /** 是否是冲突里的被裁决方（弱化显示） */
  isBackup: boolean;
}

/**
 * 结构树行：与元素页签同一份分页内容，仅按标题层级缩进。
 *
 * <p>标题按自身层级缩进，正文跟随最近的一个标题；层级读产物下发的 extra.level。
 * 文本折叠与展开复用元素页签的 {@link expanded} 与 {@link toggleExpand}，行键同为产物元素 id。
 */
const treeRows = computed<TreeRow[]>(() => {
  // 当前所属标题层级：正文跟随最近的一个标题
  let currentLevel = 0;
  return rawItems.value.map((item, index) => {
    const isTitle = item.type === 'TITLE';
    const extra = item.extra ?? {};
    const level = statNumber(extra.level);
    if (isTitle) {
      currentLevel = level ?? 1;
    }
    // 层级 1 顶格；层级 N 缩进 (N-1) 档；正文比所属标题再进一档
    const depth = isTitle ? Math.max(currentLevel - 1, 0) : currentLevel;
    const rows = statNumber(extra.rows);
    const cols = statNumber(extra.cols);
    const display = (item.display ?? '').trim();
    return {
      key: itemKeyOf(item, index),
      seq: item.seq ?? index + 1,
      typeText: elementTypeLabel(item.type),
      text: rowTextOf(item, display),
      page: pageOf(item.extra),
      grid: rows !== null && cols !== null ? `${rows}×${cols}` : '',
      indent: `${TREE_INDENT_BASE + depth * TREE_INDENT_STEP}px`,
      expandable: display !== '',
      isBackup: item.status === 'BACKUP',
    };
  });
});

/** 行文本：有正文用正文，没有正文的元素给占位说明（表格给行列数） */
function rowTextOf(item: StageContentItem, display: string): string {
  if (display !== '') {
    return display;
  }
  const extra = item.extra ?? {};
  if (item.type === 'TABLE') {
    const rows = statNumber(extra.rows);
    const cols = statNumber(extra.cols);
    return `表格 ${rows ?? '—'} 行 × ${cols ?? '—'} 列`;
  }
  if (item.type === 'IMAGE') {
    return '图片';
  }
  return '';
}

/** 产物内容项的键：优先用对齐键（组装 = 产物元素 id），缺失时按序号兜底 */
function itemKeyOf(item: StageContentItem, index: number): string {
  return item.alignKey ?? `seq-${item.seq ?? index}`;
}

/**
 * 耗时按任务起止时间现算：只在统计里没有 durationMs 时用作兜底。
 *
 * <p>解析与组装都有量好的耗时（解析取产物统计、组装取通用统计），走到这里说明统计缺失。
 */
const detailDurationMs = computed(() => {
  const value = detail.value;
  if (value?.startedAt === null || value?.finishedAt === null || value === null) {
    return null;
  }
  const start = Date.parse(value.startedAt);
  const end = Date.parse(value.finishedAt);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
    return null;
  }
  return end - start;
});

const statusText = computed(() => taskStatusLabel(detail.value?.status ?? ''));
const tone = computed(() => statusTone(detail.value?.status));
const isFailed = computed(() => tone.value === 'fail');

/** 状态徽标配色：类名写成字面量，样式检查才看得到 */
const TONE_CLASSES: Record<string, string> = {
  ok: 'is-ok',
  partial: 'is-partial',
  run: 'is-run',
  wait: 'is-wait',
  fail: 'is-fail',
};

const badgeClass = computed(() => TONE_CLASSES[tone.value] ?? 'is-wait');

/** 统计条：口径由环节配置给（固定格数，缺失项给 `—`） */
const statItems = computed(() => stageView.value.statItems(detail.value));

/**
 * 结论文案：后端给就用后端的，缺失时按状态回落。
 *
 * <p>解析环节读 `parseSummary`（统计结构固定），其余环节读通用的 `stageSummary`；
 * 两者都空且不是失败态时给 `—`。
 */
const conclusionText = computed(() => {
  const value = detail.value;
  const summary = value === null ? null : (value.stageSummary ?? parseSummaryOf(value));
  if (summary) {
    return summary;
  }
  if (isFailed.value) {
    return detail.value?.errorMsg ?? '—';
  }
  return '—';
});

/** 解析环节的专属摘要字段（其余环节没有该字段） */
function parseSummaryOf(
  value: ParseDetail | StructureDetail | PreprocessDetail | ChunkDetail,
): string | null {
  return 'parseSummary' in value ? value.parseSummary : null;
}

/** 预处理详情（用只属于它的 elements 字段判别；其余环节为 null） */
function preprocessDetailOf(
  value: ParseDetail | StructureDetail | PreprocessDetail | ChunkDetail | null,
): PreprocessDetail | null {
  return value !== null && 'elements' in value ? value : null;
}

const conclusionClass = computed(() => badgeClass.value);

/** 右栏页签：由环节配置给（解析=元素/告警/过程；组装=结构树/元素/告警/过程） */
const tabs = computed<StageTab[]>(() => stageView.value.tabs);

/** 页签键别名：模板里 `activeTab === 'warnings'` 之类的字面量比较需要它 */
type ResultTabKey = StageTabKey;

/** 环节默认页签：环节配置里页签表的第一项 */
const defaultTab = computed<ResultTabKey>(() => tabs.value[0].key);

/**
 * 当前页签：切换只换右栏内容，左栏位置与选中状态都不受影响。
 *
 * <p>打开抽屉或换环节时落到该环节的默认页签；同一环节内换运行保留用户选的页签。
 */
const activeTab = ref<ResultTabKey>(defaultTab.value);

/** 页签角标数：没有内容的页签不显示数字 */
function tabCount(key: ResultTabKey): number {
  if (key === 'elements') {
    // 角标固定全量；过滤/按页只影响列表与分页，不改变产物总数
    return fullElementTotal.value;
  }
  if (key === 'warnings') {
    return warningItems.value.length || warnings.value.length + conflicts.value.length;
  }
  if (key === 'tree') {
    return treeRows.value.length;
  }
  if (key === 'excluded') {
    return excludedCount();
  }
  if (key === 'fields') {
    return fieldCount();
  }
  if (key === 'fallback') {
    return chunkStatCount('fallbackCount') ?? fallbackPane.total;
  }
  if (key === 'parents') {
    return (chunkStatCount('parentCount') ?? 0) + (chunkStatCount('orphanCount') ?? 0);
  }
  if (key === 'sources') {
    return 0;
  }
  return steps.value.length;
}

/** 剔除内容的角标数：优先用详情统计（不必先请求列表），缺统计时用已取回的条数 */
function excludedCount(): number {
  const skipped = preprocessDetailOf(detail.value)?.summary?.chunkSkippedCount ?? null;
  return skipped ?? excludedTotal.value;
}

/** 字段页签的角标数：优先用详情统计，缺统计时用已摊平的字段行数 */
function fieldCount(): number {
  const total = preprocessDetailOf(detail.value)?.summary?.fieldCount ?? null;
  return total ?? fieldRows.value.length;
}

/** 失败建议：按错误码给下一步动作（同一执行键只能跑一次，重来要么换输入要么换策略） */
const FAIL_ADVICE: Record<string, string> = {
  PARSE_CORRUPTED:
    '文件内容无法解析：确认文件未损坏，或用带文本层的版本重新导出后，重新提交该文件（同一份文件重新提交会产生新的文件结果）',
  SCANNED_UNSUPPORTED: '纯扫描件暂不支持：请提供带文本层的 PDF（OCR 能力尚未开放）后重新提交该文件',
  RATIO_BELOW_THRESHOLD: '成功单元占比过低：检查是否有大量扫描页或乱码，换一版文件重新提交',
  STRUCTURE_EMPTY: '解析产物里没有可组装的元素（空树）：确认上游解析结果有内容后再触发组装',
  STRUCTURE_UPSTREAM_UNREADABLE:
    '读不到上游解析产物：确认上游解析已成功产出产物；本次解析已失败则需重新提交文件后再组装',
  PREPROCESS_EMPTY: '上游组装产物缺失或读不到：确认组装已成功产出产物后再触发预处理',
  PREPROCESS_FAILED: '预处理执行异常：看「过程」页签里失败的步骤，改好策略或换上游产物后重新触发',
};

const failAdvice = computed(() => {
  const code = detail.value?.errorCode ?? '';
  return (
    FAIL_ADVICE[code] ??
    '本次运行没有产物：确认文件可用后重新提交文件（同一份文件重新提交会产生新的文件结果）'
  );
});

/** 下载入口：后端 `GET /files/{fileId}` 流式返回原文件，fileId 由页面从文件结果带进来 */
const downloadReason = computed(() =>
  props.sourceFileId ? '' : '这条文件结果没有可用的源文件引用，暂时下载不了',
);

const canDownload = computed(() => Boolean(props.sourceFileId));

const downloading = ref(false);

/**
 * 下载原文件：取字节流 → 触发浏览器保存。
 *
 * <p>**不能直接开链接**：下载接口要 `Authorization` 头，用 `<a href>` 打开不会带令牌。
 * 故走统一实例取 blob，再用临时对象 URL 触发保存；触发后立刻回收 URL。
 */
async function downloadSource(): Promise<void> {
  if (!canDownload.value) {
    return;
  }
  downloading.value = true;
  copyHint.value = '';
  try {
    const blob = await getSourceFile(props.sourceFileId);
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = props.fileName;
    link.click();
    URL.revokeObjectURL(url);
  } catch {
    // 失败提示已由接口层统一拦截处理
    copyHint.value = '原文件下载失败';
  } finally {
    downloading.value = false;
  }
}

/** 当前页过滤命中数；未输入关键字时为 null（不展示"命中"） */
const pageMatched = computed(() => (keyword.value.trim() ? visibleItems.value.length : null));

const pageCount = computed(() => Math.max(1, Math.ceil(elementTotal.value / PAGE_SIZE)));

function onClose(): void {
  emit('close');
}

function toggleExpand(key: string): void {
  const next = new Set(expanded.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expanded.value = next;
}

/** 时间戳取到分钟（YYYY-MM-DD HH:mm） */
function formatClock(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '—';
}

/** 拉元素某一页；关键字过滤不参与查询（后端按页给），过滤在页内做 */
async function loadItems(): Promise<void> {
  const requestSeq = ++itemsRequestSeq;
  itemsLoading.value = true;
  itemsError.value = '';
  try {
    const data = await getStageContent(props.fileResultId, props.stage, {
      taskId: props.taskId,
      docPage: followPreview.value && previewReady.value ? previewPage.value : undefined,
      current: page.value,
      size: PAGE_SIZE,
    });
    if (requestSeq !== itemsRequestSeq) {
      return;
    }
    rawItems.value = data.items ?? [];
    elementTotal.value = data.total ?? 0;
    // 全量角标与全量可定位数：后端下发优先；旧响应缺字段时只在“当前就是全量请求”时更新
    const servedAllTotal = data.allTotal ?? null;
    if (servedAllTotal !== null) {
      allElementTotal.value = servedAllTotal;
    } else if (!followPreview.value || !previewReady.value) {
      allElementTotal.value = data.total ?? 0;
    }
    const servedLocatable = data.locatableTotal ?? null;
    if (servedLocatable !== null) {
      locatableTotal.value = servedLocatable;
    }
  } catch {
    if (requestSeq !== itemsRequestSeq) {
      return;
    }
    // 失败提示已由接口层统一拦截处理
    rawItems.value = [];
    elementTotal.value = 0;
    itemsError.value = '元素内容加载失败';
  } finally {
    if (requestSeq === itemsRequestSeq) {
      itemsLoading.value = false;
    }
  }
}

/**
 * 取原文件字节：只依赖文件引用，与解析详情各自独立（解析失败也照常预览）。
 *
 * <p>失败分三类报，各自的下一步动作不同：
 * 没有文件引用 → 数据问题；HTTP 非 2xx → 取文件问题；拿到 JSON 错误信封 → 后端拒了这次读取。
 */
async function loadSource(): Promise<void> {
  sourceError.value = '';
  sourceBlob.value = null;
  previewPage.value = 0;
  if (!props.sourceFileId) {
    sourceError.value = '这条文件结果没有可用的源文件引用';
    return;
  }
  try {
    const blob = await getSourceFile(props.sourceFileId);
    if (blob.size === 0) {
      sourceError.value = '原文件内容为空（服务端返回 0 字节）';
      return;
    }
    // 下载失败时后端仍以 200 + JSON 错误信封返回，这里把它认出来
    if (blob.type.includes('json')) {
      sourceError.value = `取原文件失败：${await errorEnvelopeText(blob)}`;
      return;
    }
    sourceBlob.value = blob;
  } catch (err) {
    sourceError.value = `取原文件失败${httpStatusText(err)}`;
  }
}

/** 取 axios 错误里的 HTTP 状态码，拼成可读后缀 */
function httpStatusText(err: unknown): string {
  const status = (err as { response?: { status?: number } }).response?.status;
  return typeof status === 'number' ? `（HTTP ${status}）` : '';
}

/** 把 JSON 错误信封读成 `code msg`；读不出来时给原文串 */
async function errorEnvelopeText(blob: Blob): Promise<string> {
  try {
    const body = JSON.parse(await blob.text()) as { code?: number; msg?: string };
    return `${body.code ?? '—'} ${body.msg ?? '服务端返回了错误信息'}`;
  } catch {
    return '服务端返回的不是文件内容';
  }
}

/**
 * 左栏翻页 → 右栏切到该页元素。
 *
 * <p>跟随开关打开时按页重取（后端 docPage 过滤）；关闭时右栏保持全量列表，只移动选中项。
 */
function onPreviewPage(pageNo: number): void {
  previewPage.value = pageNo;
  if (!followPreview.value) {
    return;
  }
  page.value = 1;
  expanded.value = new Set<string>();
  void loadItems();
}

/** 预览文档就绪：记下总页数 / 工作表数，并把首开页上报为第 1 页 */
function onPreviewLoaded(total: number): void {
  previewTotal.value = total;
  if (previewPage.value === 0) {
    previewPage.value = 1;
  }
}

/** Word / Excel 预览就绪：这两类没有页概念，栏头只显示文件信息与可定位元素数 */
function onOfficeLoaded(): void {
  previewTotal.value = 0;
}

/**
 * 右栏点元素 → 左栏定位并高亮。
 *
 * <p>按当前类型分派：PDF 画 bbox 框、Word 段落底色、Excel 单元格底色。
 * 三者都叫 `revealAnchor`（PDF 叫 `revealHighlight`），各自实现自己的定位方式。
 */
function onElementPick(key: string): void {
  activeKey.value = key;
  pickedKey.value = key;
  // 左栏是组装产物文档时，元素列表与文档是同一份产物的元素，按元素 id 对齐
  if (leftPaneKind.value === 'assembly') {
    void revealDocBlock(key);
    return;
  }
  if (previewKind.value === 'pdf') {
    void previewRef.value?.revealHighlight(key);
    return;
  }
  if (previewKind.value === 'docx') {
    void docxRef.value?.revealAnchor(key);
    return;
  }
  if (previewKind.value === 'xlsx') {
    void xlsxRef.value?.revealAnchor(key);
  }
}

/**
 * 点结构树某行 → 左栏文档滚到对应块并高亮。
 *
 * <p>两侧来自同一产物，按产物元素 id 对齐；对不上时只保留选中，不做顺序近似定位。
 */
function onTreePick(key: string): void {
  activeKey.value = key;
  pickedKey.value = key;
  void revealDocBlock(key);
}

/** 点左栏文档里的块 → 右栏对应行选中并滚入视野 */
function onDocPick(key: string): void {
  activeKey.value = key;
  pickedKey.value = key;
  void revealRightRow(key);
}

/**
 * 左栏文档按产物元素 id 定位。
 *
 * <p>目标块还没渲染时先把渲染窗口补到它（大文档不做一次性全渲染）；
 * 清洗后的正文本档只列保留元素，目标落在被剔除的元素上时先切到"看被剔除"档再定位。
 */
async function revealDocBlock(key: string): Promise<void> {
  let index = docBlocks.value.findIndex((block) => block.key === key);
  if (index < 0 && leftPaneKind.value === 'cleaned' && cleanedMode.value === 'kept') {
    cleanedMode.value = 'dropped';
    await nextTick();
    index = docBlocks.value.findIndex((block) => block.key === key);
  }
  if (index < 0) {
    return;
  }
  if (index >= docRenderLimit.value) {
    docRenderLimit.value = Math.min(docBlocks.value.length, index + DOC_RENDER_STEP);
  }
  await nextTick();
  const block = docRef.value?.querySelector(`[data-key="${CSS.escape(key)}"]`);
  if (block instanceof HTMLElement) {
    block.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

/**
 * 右栏定位到某个产物元素所在的行：目标不在当前页时先切到它所在的那一页。
 *
 * <p>页码按左栏文档里的下标换算，只在两侧条数一致时才换页（口径不同就不动，避免跳错页）。
 */
async function revealRightRow(key: string): Promise<void> {
  const onPage = rawItems.value.some((item, index) => itemKeyOf(item, index) === key);
  if (!onPage && leftPaneKind.value === 'chunks') {
    // 切片：左栏按阅读序重排，行的页序与左栏位置不同，换页要用片自己的集合内顺序号算
    const order = chunkOrderOf(key);
    const targetPage = order === null ? page.value : Math.floor((order - 1) / PAGE_SIZE) + 1;
    if (targetPage !== page.value) {
      page.value = targetPage;
      await loadItems();
    }
  } else if (!onPage && elementTotal.value === docAllBlocks.value.length) {
    const index = docAllBlocks.value.findIndex((block) => block.key === key);
    const targetPage = index < 0 ? page.value : Math.floor(index / PAGE_SIZE) + 1;
    if (targetPage !== page.value) {
      page.value = targetPage;
      await loadItems();
    }
  }
  await scrollElementIntoView(key);
}

/** 文档滚动到接近底部时追加渲染窗口 */
function onDocScroll(event: Event): void {
  const box = event.currentTarget as HTMLElement | null;
  if (box === null || docRenderLimit.value >= docBlocks.value.length) {
    return;
  }
  if (box.scrollTop + box.clientHeight >= box.scrollHeight - DOC_LOAD_MARGIN) {
    docRenderLimit.value = Math.min(docBlocks.value.length, docRenderLimit.value + DOC_RENDER_STEP);
  }
}

/** 左栏点原文 → 命中的元素滚到右栏并选中 */
function onPreviewPick(key: string | null): void {
  if (!key) {
    pickedKey.value = '';
    return;
  }
  pickedKey.value = key;
  activeKey.value = key;
  void scrollElementIntoView(key);
}

/** 把右栏某条元素滚到可见 */
async function scrollElementIntoView(key: string): Promise<void> {
  const resultPane = resultPaneRef.value;
  if (!resultPane) {
    return;
  }
  await nextTick();
  const row = resultPane.querySelector(`[data-key="${CSS.escape(key)}"]`);
  if (row instanceof HTMLElement) {
    row.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

/** 双栏同跳某页：左栏滚到该页，右栏按该页重取（跟随关闭时也跳，保证"同跳"） */
async function goDocPage(pageNo: number): Promise<void> {
  if (!canPreview.value || !previewReady.value) {
    return;
  }
  await previewRef.value?.goToPage(pageNo);
  previewPage.value = pageNo;
  page.value = 1;
  expanded.value = new Set<string>();
  await loadItems();
}

/**
 * 告警文本切分：把可识别的页码范围拆成可点片段。
 *
 * <p>识别 `第 3 页`、`第 3–5 页`、`第 3-5 页` 三种写法（后端摘要用的是全角连字符）。
 */
function warningParts(text: string): { text: string; page: number | null }[] {
  const pattern = /第\s*(\d+)(?:\s*[–—-]\s*(\d+))?\s*页/g;
  const parts: { text: string; page: number | null }[] = [];
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    const start = match.index;
    if (start > cursor) {
      parts.push({ text: text.slice(cursor, start), page: null });
    }
    parts.push({ text: match[0], page: Number(match[1]) });
    cursor = start + match[0].length;
  }
  if (cursor < text.length) {
    parts.push({ text: text.slice(cursor), page: null });
  }
  return parts.length > 0 ? parts : [{ text, page: null }];
}

function goPage(next: number): void {
  const target = Math.min(Math.max(next, 1), pageCount.value);
  if (target === page.value) {
    return;
  }
  page.value = target;
  expanded.value = new Set<string>();
  void loadItems();
}

/** 复制当前页文本：只复制右栏当前页已取回的内容，提示语明确范围 */
async function copyAll(): Promise<void> {
  const text = visibleItems.value
    .map((item) => item.text)
    .filter(Boolean)
    .join('\n');
  if (!text) {
    copyHint.value = '当前页没有可复制的文本';
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    copyHint.value = `已复制当前页 ${visibleItems.value.length} 条（完整内容请逐页复制）`;
  } catch {
    copyHint.value = '浏览器拒绝了剪贴板访问，请手动选择文本复制';
  }
}

const copyHint = ref('');

// 跟随开关：打开时按预览当前页重取，关闭时回全量列表
watch(followPreview, () => {
  page.value = 1;
  expanded.value = new Set<string>();
  void loadItems();
});

// 预览就绪（拿到第一页）后，跟随模式要按该页重取一次
watch(previewReady, (ready) => {
  if (ready && followPreview.value) {
    page.value = 1;
    void loadItems();
  }
});

// 切到「剔除内容」页签时按需取列表（角标数已由详情统计给出，不必打开就请求）
watch(activeTab, (tab) => {
  if (tab === 'excluded' && !excludedLoaded.value) {
    void loadExcluded();
  }
});

// 切到切片过滤页签时按需取数（兜底片与父片·孤儿各自一份）
watch(activeTab, (tab) => {
  if ((tab === 'fallback' || tab === 'parents') && !activeChunkPane.value.loaded) {
    void activeChunkPane.value.load();
  }
});

/** 上一次已落到默认页签的环节（null = 抽屉当前没打开） */
let tabStage: string | null = null;

/**
 * 抽屉可见性同步浏览器页签标题：打开时用「文件名 · 环节名」覆盖，关闭时落回路由标题。
 *
 * <p>只改标题，不参与抽屉内容的渲染与取数。
 */
function syncDrawerTitle(): void {
  if (props.visible && props.fileName !== '') {
    pushEntityTitle(props.fileName, `${stageLabel(props.stage)}环节`);
    return;
  }
  restoreTitle();
}

watch(() => [props.visible, props.fileName, props.stage] as const, syncDrawerTitle, {
  immediate: true,
});

// 打开或切换到另一次运行时重新取数；关闭时不请求
watch(
  () => [props.visible, props.fileResultId, props.taskId, props.stage] as const,
  ([visible]) => {
    if (!visible) {
      // 关闭时忘掉已落位的环节，再次打开仍从默认页签开始
      tabStage = null;
      return;
    }
    detail.value = null;
    detailError.value = '';
    detailLoading.value = true;
    rawItems.value = [];
    allElementTotal.value = 0;
    locatableTotal.value = 0;
    page.value = 1;
    keyword.value = '';
    expanded.value = new Set<string>();
    copyHint.value = '';
    followPreview.value = true;
    previewPage.value = 0;
    activeKey.value = '';
    pickedKey.value = '';
    sourceError.value = '';
    // 文档渲染窗口回到起点：换运行后按新产物重新按需渲染
    docRenderLimit.value = DOC_RENDER_STEP;
    // 清洗后的正文回到"只看保留"档；剔除内容重新按需取
    cleanedMode.value = 'kept';
    excludedPage.value = 1;
    excludedItems.value = [];
    excludedTotal.value = 0;
    excludedError.value = '';
    excludedLoaded.value = false;
    // 切片结果回到"全部"档；两个切片过滤页签重新按需取数
    chunkMode.value = 'all';
    fallbackPane.reset();
    parentPane.reset();
    // 打开或换环节时落到该环节的默认页签；同一环节内换运行保留用户选的页签
    if (tabStage !== props.stage) {
      activeTab.value = defaultTab.value;
      tabStage = props.stage;
    }
    void getStageDetail(props.stage, props.fileResultId, props.taskId)
      .then((data) => {
        detail.value = data;
      })
      .catch(() => {
        detailError.value = `${stageView.value.runningText.replace('中', '')}详情加载失败`;
      })
      .finally(() => {
        detailLoading.value = false;
      });
    if (leftPaneKind.value === 'source') {
      void loadSource();
    }
    void loadItems();
    // 打开（或换运行）后量一次双栏宽度，按记住的比例定左栏宽
    sourceWidth.value = null;
    void nextTick(() => {
      measureSplit();
    });
  },
  { immediate: true },
);

onMounted(() => {
  window.addEventListener('resize', onWindowResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWindowResize);
});
</script>

<style scoped lang="css">
/* 遮罩：只负责把抽屉压在链图之上并承接"点外部关闭" */
.parse-drawer-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  justify-content: flex-end;
  background: rgb(7 11 20 / 62%);
}

/*
 * 抽屉本体：宽屏取 1440px，窄屏按视口收窄且不溢出。
 *
 * 四个固定分区（头部 / 统计条 / 双栏 / 底部）纵向排开，高度锁在视口内、
 * overflow 隐藏：滚动只发生在中间双栏各自的容器里，外层不出滚动条。
 */
.parse-drawer {
  display: flex;
  width: min(1440px, 100vw);
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--kb-line);
  background: var(--kb-bg-1);
  box-shadow: -12px 0 32px rgb(0 0 0 / 45%);
}

.parse-drawer-head {
  display: flex;
  flex: none;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--kb-line);
}

.parse-drawer-title {
  display: flex;
  min-width: 0;
  gap: 10px;
  align-items: center;
}

.parse-drawer-file {
  overflow: hidden;
  font-size: 15px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态徽标：底色与文字色按状态 tone 给（is-* 规则在全局 chain-graph.css —— 
   它们由计算属性动态给出，静态检查看不到 scoped 里的声明） */
.parse-drawer-badge {
  flex: none;
  padding: 2px 8px;
  border-radius: 99px;
  font-size: 11px;
  font-weight: 600;
}

.parse-drawer-x {
  flex: none;
  padding: 4px 10px;
  border: none;
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-x:hover {
  background: var(--kb-line-2);
}

.parse-drawer-ident {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 16px;
  padding: 9px 18px;
  border-bottom: 1px solid var(--kb-line);
  color: var(--kb-text-3);
  font-size: 11px;
}

/* 统计条：固定分区，内容一行放不下就换行，自身不滚动 */
.parse-drawer-statbar {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px 18px;
  align-items: baseline;
  padding: 10px 18px;
  border-bottom: 1px solid var(--kb-line);
}

/* 失败条：固定分区，把错误码 / 说明 / 建议压成一行一行的短句 */
.parse-drawer-failbar {
  flex: none;
  margin: 10px 18px 0;
  gap: 6px;
}

/*
 * 中间双栏：唯一会伸缩的分区。
 * flex: 1 + min-height: 0 是关键 —— 少了 min-height，子内容会把这一区撑高，
 * 于是整页出现滚动条。
 */
.parse-drawer-body {
  display: flex;
  flex: 1;
  min-height: 0;
  gap: 0;
  overflow: hidden;
}

/* 左栏：原文预览（失败/不支持只影响本栏）。
   width/min-width 是"还没量到容器"时的默认值（62% / 320px）；量到之后由 :style
   写内联宽度与 min-width 覆盖。min-width 一旦由脚本接管，拖动的最小值就以脚本的
   320px 为准 —— 与样式里的默认值同口径，不会出现"能拖到 320 却被 420 挡住"。 */
.parse-drawer-pane-source {
  display: flex;
  width: 62%;
  min-width: 320px;
  flex: none;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--kb-line);
}

/*
 * 分隔条：两栏之间的竖直拖拽条。
 * 视觉 6px，实际命中区靠 ::before 向两侧各扩 3px（太细不好点）。
 */
.parse-drawer-splitter {
  position: relative;
  flex: none;
  width: 6px;
  background: var(--kb-bg-1);
  cursor: col-resize;
  touch-action: none;
}

.parse-drawer-splitter::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: -3px;
  width: 12px;
  content: '';
}

.parse-drawer-splitter:hover,
.parse-drawer-splitter:focus-visible,
.parse-drawer-splitter.is-dragging {
  background: var(--kb-primary);
  outline: none;
}

/* 拖动过程中锁掉文字选中，避免拖到一半把标签文字选蓝 */
.parse-drawer-body.is-dragging {
  user-select: none;
}

/* 栏头：固定在本区顶部，不随内容滚动 */
.parse-drawer-pane-head {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 4px 10px;
  align-items: baseline;
  padding: 12px 14px 8px;
}

/* 清洗后正文的档位切换：只看保留 / 看被剔除（被剔除的块灰化划线留在原位） */
.parse-drawer-seg {
  display: flex;
  flex: none;
  margin-left: auto;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: var(--kb-surface);
}

.parse-drawer-seg-btn {
  padding: 2px 8px;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
  cursor: pointer;
}

.parse-drawer-seg-btn.is-on {
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-weight: 600;
}

/* 预览组件占满栏头之下的空间；它内部自带工具条（固定）与画布（滚动） */
.parse-drawer-pane-fill {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  padding: 0 14px 12px;
}

/* 右栏：解析结果（页签固定，页签内容各自滚动） */
.parse-drawer-pane-result {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
}

/* 页签条：固定在本区顶部 */
.parse-drawer-tabs {
  display: flex;
  flex: none;
  gap: 4px;
  padding: 8px 14px 0;
  border-bottom: 1px solid var(--kb-line);
}

.parse-drawer-tab {
  padding: 6px 12px;
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-tab:hover {
  color: var(--kb-text-1);
}

/* 当前页签 */
.parse-drawer-tab.is-on {
  border-bottom-color: var(--kb-primary);
  color: var(--kb-primary);
  font-weight: 600;
}

.parse-drawer-tab-num {
  margin-left: 5px;
  padding: 0 5px;
  border-radius: 99px;
  background: var(--kb-tint);
  font-size: 10px;
}

/* 页签面板：头部/底部固定，中间那层滚动 */
.parse-drawer-tabpane {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

/* 页签内的固定工具行（搜索 / 跟随开关） */
.parse-drawer-tabhead {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--kb-line);
}

/* 页签内的固定底行（翻页） */
.parse-drawer-tabfoot {
  display: flex;
  flex: none;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding: 8px 14px;
  border-top: 1px solid var(--kb-line);
}

/*
 * 页签内容的滚动容器：本区唯一的滚动条就在这里。
 * min-height: 0 让它在 flex 里真正被压缩，内容才不会把面板顶出去。
 */
.parse-drawer-tabscroll {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 9px;
  overflow: hidden auto;
  padding: 12px 14px;
}

.parse-drawer-block {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.parse-drawer-block-title {
  display: flex;
  margin: 0;
  flex: none;
  gap: 10px;
  align-items: baseline;
  color: var(--kb-text-2);
  font-size: 12px;
  font-weight: 650;
}

/* 结论文案沿用状态色（规则同样在全局 chain-graph.css） */
.parse-drawer-conclusion {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.parse-drawer-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
}

.parse-drawer-stat {
  display: flex;
  gap: 5px;
  align-items: baseline;
  color: var(--kb-text-3);
  font-size: 11px;
}

.parse-drawer-stat-value {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 13px;
  font-weight: 600;
}

.parse-drawer-warnings {
  display: flex;
  margin: 0;
  padding: 0;
  gap: 5px;
  flex-direction: column;
  list-style: none;
}

.parse-drawer-warning {
  padding: 6px 10px;
  border-left: 2px solid var(--kb-warn);
  background: rgb(251 191 36 / 8%);
  color: var(--kb-text-2);
  font-size: 12px;
}

/* 结构化告警的 code/level 前缀：弱化展示，保留可扫读的告警类型 */
.parse-drawer-warning-code {
  margin-right: 8px;
  color: var(--kb-warn);
  font-size: 11px;
  font-weight: 650;
}

/* 告警里的页码范围：点了左右两栏一起跳到该页 */
.parse-drawer-page-link {
  padding: 0 2px;
  border: none;
  background: transparent;
  color: var(--kb-primary);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  text-decoration: underline;
  cursor: pointer;
}

.parse-drawer-page-link:hover {
  color: var(--kb-text-1);
}

/* 跟随预览当前页的开关 */
.parse-drawer-check {
  display: flex;
  gap: 6px;
  align-items: center;
  color: var(--kb-text-3);
  font-size: 11px;
  cursor: pointer;
}

.parse-drawer-block-bad {
  padding: 12px 14px;
  border: 1px solid rgb(248 113 113 / 28%);
  border-radius: 10px;
  background: rgb(248 113 113 / 8%);
}

.parse-drawer-error-code {
  margin: 0;
  color: var(--kb-danger);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
}

.parse-drawer-error-msg {
  margin: 0;
  color: var(--kb-text-1);
  font-size: 12px;
}

.parse-drawer-advice {
  margin: 0;
  color: var(--kb-text-2);
  font-size: 12px;
  line-height: 1.6;
}

.parse-drawer-steps {
  display: flex;
  margin: 0;
  padding: 0;
  gap: 6px;
  flex-direction: column;
  list-style: none;
}

.parse-drawer-step {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: baseline;
  padding: 6px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: var(--kb-surface);
  font-size: 11px;
}

/* 失败的子步骤：左侧红条 + 红字，一眼看出卡在哪一步 */
.parse-drawer-step.is-bad {
  border-left: 2px solid var(--kb-danger);
}

.parse-drawer-step-name {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-weight: 600;
}

.parse-drawer-step-meta {
  color: var(--kb-text-3);
}

.parse-drawer-step-error {
  flex-basis: 100%;
  color: var(--kb-danger);
}

.parse-drawer-search {
  width: 220px;
  max-width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-1);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.parse-drawer-search::placeholder {
  color: var(--kb-text-4);
}

.parse-drawer-elements {
  display: flex;
  margin: 0;
  padding: 0;
  gap: 8px;
  flex-direction: column;
  list-style: none;
}

.parse-drawer-element {
  padding: 8px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: var(--kb-surface);
  cursor: pointer;
}

/* 当前重点标注的元素（左栏同时画高亮框） */
.parse-drawer-element.is-active {
  border-color: var(--kb-warn);
  background: rgb(251 191 36 / 8%);
}

/* 在原文上点选命中的元素 */
.parse-drawer-element.is-picked {
  border-color: var(--kb-primary);
  box-shadow: 0 0 0 1px var(--kb-primary) inset;
}

/* 处置状态徽标（预处理：仅标记 / 剔除·原因） */
.parse-drawer-state {
  flex: none;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--kb-surface);
  color: var(--kb-text-3);
  font-size: 10px;
  font-weight: 600;
}

/* 切片行号（切片页签与两个过滤页签共用） */
.parse-drawer-chunk-seq {
  flex: none;
  color: var(--kb-text-1);
  font-size: 12px;
  font-weight: 650;
}

/* 剔除内容行的正文：保留换行，长文本可读 */
.parse-drawer-excluded-text {
  margin: 6px 0 0;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 字段一览：一行一个字段（类型 + 值 + 来源元素） */
.parse-drawer-fields {
  display: flex;
  margin: 0;
  padding: 0;
  gap: 6px;
  flex-direction: column;
  list-style: none;
}

.parse-drawer-field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  padding: 6px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 8px;
  background: var(--kb-surface);
}

.parse-drawer-field-value {
  color: var(--kb-text-1);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
}

.parse-drawer-element-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
}

/* 类型徽标：元素列表靠它一眼分辨类型 */
.parse-drawer-type {
  flex: none;
  padding: 1px 7px;
  border-radius: 5px;
  background: var(--kb-tint);
  color: var(--kb-primary);
  font-size: 11px;
  font-weight: 600;
}

.parse-drawer-element-meta {
  color: var(--kb-text-3);
  font-family: ui-monospace, 'JetBrains Mono', Consolas, monospace;
  font-size: 10px;
}

.parse-drawer-element-meta.is-warn {
  color: var(--kb-warn);
  font-weight: 650;
}

.parse-drawer-toggle {
  margin-left: auto;
  padding: 1px 8px;
  border: 1px solid var(--kb-line-2);
  border-radius: 6px;
  background: none;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 10px;
  cursor: pointer;
}

.parse-drawer-toggle:hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

/*
 * 可展开文本块：默认两行封顶（超出省略），展开后放开。
 * 用 `-webkit-box` + `line-clamp`：折叠态两行，展开态由 is-expanded 还原成普通块。
 * 元素页签与结构树页签共用这一份规则，行键同为产物元素 id。
 */
.parse-drawer-element-text,
.parse-drawer-tree-text {
  display: -webkit-box;
  margin: 6px 0 0;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.parse-drawer-element-text.is-expanded,
.parse-drawer-tree-text.is-expanded {
  display: block;
  -webkit-line-clamp: unset;
  line-clamp: unset;
}

/* 解析元素行的表格网格：单元格文本原样展示（不设格数上限，大表整表渲染） */
.parse-drawer-element-grid {
  width: 100%;
  margin-top: 8px;
  border-collapse: collapse;
  table-layout: fixed;
}

.parse-drawer-element-cell {
  padding: 5px 7px;
  border: 1px solid var(--kb-line);
  color: var(--kb-text-1);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  vertical-align: top;
}

.parse-drawer-element-cell.is-head {
  background: rgb(255 255 255 / 3%);
  color: var(--kb-text-2);
}

.parse-drawer-page-btn {
  padding: 4px 12px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-page-btn:disabled {
  color: var(--kb-text-4);
  cursor: not-allowed;
}

.parse-drawer-page-btn:not(:disabled):hover {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.parse-drawer-page-info {
  color: var(--kb-text-3);
  font-size: 11px;
}

.parse-drawer-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 11px;
}

.parse-drawer-hint-bad {
  color: var(--kb-danger);
}

.parse-drawer-foot {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 12px 18px;
  border-top: 1px solid var(--kb-line);
}

.parse-drawer-btn {
  padding: 6px 14px;
  border: 1px solid var(--kb-line-2);
  border-radius: 8px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  cursor: pointer;
}

.parse-drawer-btn:disabled {
  color: var(--kb-text-4);
  cursor: not-allowed;
}

.parse-drawer-btn-primary {
  border-color: transparent;
  background: var(--kb-primary);
  color: var(--kb-btn-text);
  font-weight: 600;
}

.parse-drawer-btn-primary:hover {
  box-shadow: 0 0 12px var(--kb-glow);
}

/* 结构树：页签内的滚动列表；行头与文本块和元素页签同一套，另按层级给左内边距 */
.parse-drawer-tree {
  margin: 0;
  padding: 4px 0;
  list-style: none;
}

.parse-drawer-tree-row {
  padding: 6px 10px;
  border-bottom: 1px solid var(--kb-line);
  cursor: pointer;
}

.parse-drawer-tree-row:hover {
  background: var(--kb-bg-2);
}

/* 当前选中的结构行（.is-on 由脚本按选中键加，故样式在全局 chain-graph.css） */

.parse-drawer-tree-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
}

/* 冲突里的被裁决方：弱化，不与被采用的一路混淆 */
.parse-drawer-tree-row.is-backup .parse-drawer-tree-text {
  color: var(--kb-text-4);
  text-decoration: line-through;
}

.parse-drawer-subtitle {
  margin: 10px 10px 6px;
  color: var(--kb-text-3);
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
  font-weight: 600;
}

.parse-drawer-conflict-pair {
  margin-right: 6px;
  color: var(--kb-warn);
}
</style>
