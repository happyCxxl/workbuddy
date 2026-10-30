<template>
  <div class="page assistant-page">
    <div class="chat-wrap" :class="{ 'sessions-collapsed': !sessionsOpen, switching }">
      <!-- 工作区列表 -->
      <div class="sessions-panel">
        <div class="sessions-head">
          <span class="sessions-title">工作区</span>
          <span class="page-spacer"></span>
          <button
            class="head-icon"
            :class="{ 'head-icon-on': searchVisible }"
            type="button"
            title="搜索工作区"
            @click="searchVisible = !searchVisible"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
            >
              <circle cx="7" cy="7" r="4.2" />
              <path d="m10.2 10.2 3 3" />
            </svg>
          </button>
          <button
            class="head-icon"
            :class="{ 'head-icon-on': onlyWithSessions }"
            type="button"
            title="仅显示有会话的工作区"
            @click="onlyWithSessions = !onlyWithSessions"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
            >
              <path d="M2.5 4h11M4.5 8h7M6.5 12h3" />
            </svg>
          </button>
          <button class="head-icon" type="button" title="新增工作区" @click="openWorkspaceDialog">
            <svg
              width="16"
              height="16"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
            >
              <path d="M2 4.5h4l1.3 1.5H13v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
              <path d="M10 5.6v4.2M7.9 7.7h4.2" />
            </svg>
          </button>
        </div>
        <div v-if="searchVisible" class="workspace-search">
          <el-input
            v-model="workspaceKeyword"
            size="small"
            placeholder="搜索工作区名称或路径"
            clearable
          />
        </div>
        <div class="sessions-list">
          <div v-for="group in sessionGroups" :key="group.key" class="session-group">
            <div class="group-head" @click="toggleGroup(group.key)">
              <span class="group-folder">
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.4"
                >
                  <path d="M2 4.5h4l1.3 1.5H14v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
                  <path d="M2 6h12" />
                </svg>
              </span>
              <span class="group-arrow" :class="{ 'group-arrow-open': !collapsedGroups[group.key] }"
                >▸</span
              >
              <span class="group-name" :title="group.path">{{ group.name }}</span>
              <span v-if="group.workspaceId" class="group-actions" @click.stop>
                <el-dropdown trigger="click" @command="handleGroupCommand($event, group)">
                  <button class="group-icon" type="button" title="更多">⋯</button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="copy">复制路径</el-dropdown-item>
                      <el-dropdown-item command="rename">重命名</el-dropdown-item>
                      <el-dropdown-item command="delete-workspace">删除工作区</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
                <button
                  class="group-icon group-add"
                  type="button"
                  title="在该工作区新建会话"
                  @click.stop="handleNewSession(group)"
                >
                  ＋
                </button>
              </span>
            </div>
            <div v-show="!collapsedGroups[group.key]" class="group-items">
              <p v-if="group.items.length === 0" class="group-empty">暂无会话</p>
              <button
                v-for="item in group.items"
                :key="item.id"
                type="button"
                class="session-item"
                :class="{ 'session-item-on': item.id === currentId }"
                @click="selectSession(item)"
              >
                <span class="session-title">{{ item.title || '未命名会话' }}</span>
                <span class="session-time">{{ formatTime(item.updated_at) }}</span>
                <span class="session-rename" title="重命名" @click.stop="handleRename(item)"
                  >✎</span
                >
                <span
                  class="session-delete"
                  title="删除会话"
                  @click.stop="handleDeleteSession(item)"
                  >✕</span
                >
              </button>
            </div>
          </div>
          <p v-if="workspaces.length === 0 && sessions.length === 0" class="session-empty">
            暂无工作区 暂无工作区
          </p>
        </div>
      </div>

      <!-- 切换会话栏时：发光扫描线从左到右擦除 / 从右到左重绘网格 -->
      <div v-show="switching" ref="wipeEl" class="grid-wipe" aria-hidden="true">
        <span class="wipe-edge"></span>
      </div>

      <!-- 对话区 -->
      <div class="chat-panel">
        <div class="chat-head">
          <button
            class="head-icon"
            type="button"
            :title="sessionsOpen ? '收起会话栏' : '展开会话栏'"
            @click="toggleSessions"
          >
            <svg
              width="16"
              height="16"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.5"
            >
              <path d="M2.5 4h11M2.5 8h11M2.5 12h11" />
            </svg>
          </button>
          <span class="chat-title">{{ current?.title || '选择或新建一个会话' }}</span>
          <span v-if="current" class="chat-dir">{{ current.work_dir }}</span>
          <span class="page-spacer"></span>
          <el-button v-if="current" size="small" text @click="openTrajectory">轨迹</el-button>
          <el-button v-if="current" size="small" text @click="openSubSessions">
            后台任务
            <span v-if="runningSubCount > 0" class="sub-count">{{ runningSubCount }}</span>
          </el-button>
          <span v-if="running" class="chat-status">执行中 · 第 {{ stepLabel }} 步</span>
        </div>

        <div ref="messageBox" class="messages">
          <p v-if="messages.length === 0" class="messages-empty">
            发一句话开始干活。可乐会按人格版本 {{ personaLabel }} 说话，按权限档执行工具。
          </p>
          <button v-if="hiddenTurns > 0" class="turn-earlier" type="button" @click="showEarlier">
            显示更早的 {{ hiddenTurns }} 轮
          </button>
          <section v-for="view in pagedTurns" :key="view.key" class="turn">
            <div v-if="view.user" class="message message-user">
              <div class="message-bubble">{{ view.user.text }}</div>
            </div>
            <div v-if="view.steps.length > 0" class="process">
              <button
                class="process-head"
                :class="{ 'is-running': view.running }"
                type="button"
                @click="toggleProcess(view.key)"
              >
                <span class="process-chevron">{{ isOpen(view) ? '▾' : '▸' }}</span>
                <span class="process-title">{{ view.running ? '执行中' : '工作步骤' }}</span>
                <span class="process-meta">
                  {{ view.steps.length }} 步
                  <template v-if="turnMeta[view.turnSeq ?? 0]?.duration">
                    · {{ turnMeta[view.turnSeq ?? 0]?.duration }}
                  </template>
                  <template v-if="turnMeta[view.turnSeq ?? 0]?.tokens">
                    · {{ turnMeta[view.turnSeq ?? 0]?.tokens }} tok
                  </template>
                </span>
              </button>
              <div v-show="isOpen(view)" class="process-body">
                <div v-for="(step, stepIndex) in view.steps" :key="stepIndex" class="step">
                  <div v-if="view.steps.length > 1" class="step-label">
                    第 {{ stepIndex + 1 }} 步
                  </div>
                  <details v-if="step.thinking" class="reasoning">
                    <summary class="reasoning-head">
                      <span class="reasoning-label">思考</span>
                      <span class="reasoning-preview">{{ preview(step.thinking, 80) }}</span>
                    </summary>
                    <pre class="reasoning-body">{{ step.thinking }}</pre>
                  </details>
                  <MarkdownView v-if="step.text" :content="step.text" />
                  <details
                    v-for="(tool, toolIndex) in step.tools"
                    :key="toolIndex"
                    class="tool-card"
                    :class="{ 'tool-fail': Boolean(tool.error) }"
                  >
                    <summary class="tool-head">
                      <ToolIcon :name="tool.name" class="tool-icon" />
                      <span class="tool-name">{{ tool.name }}</span>
                      <span class="tool-args">{{ tool.args }}</span>
                      <span class="tool-state">
                        {{ tool.error ? '失败' : tool.result ? '完成' : '执行中' }}
                        <template v-if="tool.duration"> · {{ tool.duration }}</template>
                      </span>
                    </summary>
                    <div class="tool-body">
                      <p class="tool-label">参数</p>
                      <pre class="tool-pre">{{ prettyJson(tool.args) }}</pre>
                      <template v-if="tool.error">
                        <p class="tool-label">错误</p>
                        <pre class="tool-pre tool-error">{{ tool.error }}</pre>
                      </template>
                      <template v-else-if="tool.result">
                        <p class="tool-label">结果</p>
                        <pre class="tool-pre">{{ tool.result }}</pre>
                      </template>
                    </div>
                  </details>
                  <div v-if="step.permission" class="message-perm">
                    <div class="perm-title">⚠ 需要你确认：{{ step.permission.name }}</div>
                    <div class="perm-reason">{{ step.permission.reason }}</div>
                    <div class="perm-actions">
                      <el-button
                        size="small"
                        type="primary"
                        @click="decidePermission(step, 'allowed-once')"
                      >
                        允许一次
                      </el-button>
                      <el-button size="small" @click="decidePermission(step, 'allowed-always')">
                        始终允许（本目录）
                      </el-button>
                      <el-button
                        size="small"
                        type="danger"
                        plain
                        @click="decidePermission(step, 'rejected')"
                      >
                        拒绝
                      </el-button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            <div v-if="view.answer" class="message message-assistant message-answer">
              <MarkdownView :content="view.answer.text" />
            </div>
            <div v-if="view.answer && !view.running" class="turn-footer">
              <el-popover placement="top-start" width="300" trigger="click">
                <template #reference>
                  <button class="turn-meta" type="button">
                    <svg
                      class="turn-icon"
                      width="12"
                      height="12"
                      viewBox="0 0 16 16"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.4"
                    >
                      <ellipse cx="8" cy="3.5" rx="5" ry="2" />
                      <path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9" />
                      <path d="M3 8c0 1.1 2.2 2 5 2s5-.9 5-2" />
                    </svg>
                    <template v-if="turnMeta[view.turnSeq ?? 0]?.duration">
                      {{ turnMeta[view.turnSeq ?? 0]?.duration }}
                    </template>
                    <template v-if="turnMeta[view.turnSeq ?? 0]?.tokens">
                      · {{ compactTokens(turnMeta[view.turnSeq ?? 0]?.tokens ?? 0) }} tok
                    </template>
                  </button>
                </template>
                <div class="usage-pop">
                  <div class="usage-pop-head">
                    <span class="usage-pop-title">本轮用量</span>
                    <span class="usage-pop-total">
                      {{ (turnMeta[view.turnSeq ?? 0]?.tokens ?? 0).toLocaleString() }} tok
                    </span>
                  </div>
                  <div class="usage-pop-row">
                    <span>提供方 / 模型</span><span>{{ turnModels(view) }}</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>缓存命中</span><span>{{ turnCacheRate(view) }}%</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>未缓存输入</span
                    ><span>{{ turnMeta[view.turnSeq ?? 0]?.cacheMiss ?? 0 }} tok</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>缓存读取</span
                    ><span>{{ turnMeta[view.turnSeq ?? 0]?.cacheHit ?? 0 }} tok</span>
                  </div>
                  <div class="usage-pop-row"><span>缓存写入</span><span>0 tok</span></div>
                  <div class="usage-pop-row">
                    <span>输出</span
                    ><span>{{ turnMeta[view.turnSeq ?? 0]?.completion ?? 0 }} tok</span>
                  </div>
                </div>
              </el-popover>
              <span class="turn-actions">
                <button class="turn-action" type="button" @click="copyAnswer(view)">复制</button>
              </span>
            </div>
          </section>
        </div>

        <div class="composer">
          <el-input
            v-model="input"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 8 }"
            resize="none"
            placeholder="发消息或创建任务 · Shift+Enter 换行"
            :disabled="!current"
            @keydown.enter.exact.prevent="handleSend"
          />
          <div class="composer-row">
            <div class="composer-hints">
              <el-popover placement="top-start" width="220" trigger="click">
                <template #reference>
                  <button class="hint-link" type="button" :disabled="!current">
                    <svg
                      class="hint-icon"
                      width="12"
                      height="12"
                      viewBox="0 0 16 16"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.4"
                    >
                      <path d="M8 1.5l5 2.5v4c0 3-2.2 5.3-5 6.5-2.8-1.2-5-3.5-5-6.5v-4z" />
                    </svg>
                    权限 {{ presetLabel(currentPreset) }}
                  </button>
                </template>
                <div class="preset-box">
                  <button
                    v-for="option in PRESET_OPTIONS"
                    :key="option.value"
                    type="button"
                    class="preset-item"
                    :class="{ 'preset-item-on': currentPreset === option.value }"
                    @click="changePreset(option.value)"
                  >
                    <span class="preset-name">{{ option.label }}</span>
                    <span v-if="currentPreset === option.value" class="preset-check">✓</span>
                  </button>
                </div>
              </el-popover>
              <el-popover placement="top-start" width="280" trigger="click">
                <template #reference>
                  <button class="hint-link" type="button" :disabled="!current">
                    <svg
                      class="hint-icon"
                      width="12"
                      height="12"
                      viewBox="0 0 16 16"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.4"
                    >
                      <ellipse cx="8" cy="3.5" rx="5" ry="2" />
                      <path d="M3 3.5v9c0 1.1 2.2 2 5 2s5-.9 5-2v-9" />
                      <path d="M3 8c0 1.1 2.2 2 5 2s5-.9 5-2" />
                    </svg>
                    用量 {{ compactTokens(totalTokens) }} tok
                  </button>
                </template>
                <div class="usage-pop">
                  <div class="usage-pop-head">
                    <span class="usage-pop-title">Token 用量</span>
                    <span class="usage-pop-total">{{ totalTokens }} tok</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>缓存命中</span><span>{{ cacheRate ?? 0 }}%</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>未缓存输入</span>
                    <span>{{ usage?.prompt_cache_miss_tokens ?? 0 }} tok</span>
                  </div>
                  <div class="usage-pop-row">
                    <span>缓存读取</span>
                    <span>{{ usage?.prompt_cache_hit_tokens ?? 0 }} tok</span>
                  </div>
                  <div class="usage-pop-row"><span>缓存写入</span><span>0 tok</span></div>
                  <div class="usage-pop-row">
                    <span>输出</span><span>{{ usage?.completion_tokens ?? 0 }} tok</span>
                  </div>
                </div>
              </el-popover>
            </div>
            <el-button v-if="running" type="danger" plain @click="handleStop">■ 停止</el-button>
            <el-button
              v-else
              size="small"
              plain
              :disabled="!current || !input.trim()"
              @click="handleBackground"
            >
              转后台
            </el-button>
            <el-button
              v-if="!running"
              type="primary"
              :disabled="!current || !input.trim()"
              @click="handleSend"
            >
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>

    <el-dialog v-model="workspaceDialogVisible" title="新增工作区" width="520px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="工作区目录（会话的文件操作限定在此目录）">
          <div class="workdir-row">
            <el-input v-model="workspacePath" placeholder="请选择目录，或直接输入绝对路径" />
            <el-button @click="openDirPicker">选择目录</el-button>
          </div>
          <div v-if="recentDirs.length > 0" class="recent-dirs">
            <span class="recent-label">最近：</span>
            <button
              v-for="dir in recentDirs"
              :key="dir"
              type="button"
              class="dir-chip"
              :class="{ 'dir-chip-on': workspacePath === dir }"
              :title="dir"
              @click="workspacePath = dir"
            >
              {{ baseName(dir) }}
            </button>
          </div>
        </el-form-item>
      </el-form>
      <p class="new-hint">
        工作区用于归置会话；创建后会自动打开一个新会话，之后也可在工作区点「＋」再建。
      </p>
      <template #footer>
        <el-button @click="workspaceDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!workspacePath.trim()" @click="handleCreateWorkspace">
          创建工作区
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="dirDialogVisible" title="选择工作目录" width="560px">
      <div class="dir-bar">
        <el-button size="small" :disabled="dirParent === null" @click="goUpDir">上一级</el-button>
        <span class="dir-path" :title="dirPath || '此电脑'">{{ dirPath || '此电脑' }}</span>
      </div>
      <div class="dir-list">
        <p v-if="dirEntries.length === 0" class="dir-empty">没有子目录。</p>
        <button
          v-for="entry in dirEntries"
          :key="entry.path"
          type="button"
          class="dir-item"
          @click="enterDir(entry.path)"
        >
          <span class="dir-icon">
            <svg
              width="14"
              height="14"
              viewBox="0 0 16 16"
              fill="none"
              stroke="currentColor"
              stroke-width="1.4"
            >
              <path d="M2 4.5h4l1.3 1.5H14v6a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1z" />
              <path d="M2 6h12" />
            </svg>
          </span>
          <span class="dir-name">{{ entry.name }}</span>
        </button>
      </div>
      <template #footer>
        <el-button @click="dirDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!dirPath" @click="chooseCurrentDir">
          选择此目录
        </el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="trajectoryVisible" title="会话轨迹" size="76%">
      <div class="trajectory-shell">
        <p v-if="trailTurns.length === 0" class="trajectory-empty">还没有活动。</p>
        <template v-else>
          <button
            v-if="hiddenTrailTurns > 0"
            class="turn-earlier"
            type="button"
            @click="showEarlierTrail"
          >
            显示更早的 {{ hiddenTrailTurns }} 轮
          </button>
          <div
            class="overview"
            @wheel.prevent="onOverviewWheel"
            @contextmenu.prevent="clearTrailView"
            @mousedown="onOverviewDown"
            @mousemove="onOverviewMove"
            @mouseup="onOverviewUp"
            @mouseleave="onOverviewUp"
          >
            <div class="overview-toolbar">
              <span class="overview-hint">滚轮缩放 · 拖选区间筛选 · 右键清除</span>
              <span class="overview-tools">
                <button class="overview-zoom" type="button" @click="zoomTrail(0.8)">＋</button>
                <button class="overview-zoom" type="button" @click="zoomTrail(1.25)">－</button>
                <button class="overview-zoom" type="button" @click="clearTrailView">重置</button>
              </span>
            </div>
            <div class="overview-ruler">
              <span class="overview-label"></span>
              <div class="overview-ruler-track">
                <span
                  v-for="tick in rulerTicks"
                  :key="tick.key"
                  class="overview-tick"
                  :style="{ left: tick.left }"
                  >{{ tick.label }}</span
                >
              </div>
              <span class="overview-duration"></span>
            </div>
            <div v-for="turn in pagedTrailTurns" :key="turn.key" class="overview-row">
              <span class="overview-label">第 {{ turn.index }} 轮</span>
              <div class="overview-track">
                <div
                  v-if="trailSelection"
                  class="overview-band"
                  :style="bandStyle(trailSelection)"
                ></div>
                <button
                  v-for="node in turn.nodes.filter((item) => item.kind !== 'user')"
                  :key="node.key"
                  type="button"
                  class="overview-span"
                  :data-kind="node.kind"
                  :style="spanStyle(node)"
                  :title="`${node.badge} · ${node.title}`"
                  @click="selectNode(node)"
                ></button>
              </div>
              <span class="overview-duration">{{ turn.duration }}</span>
            </div>
          </div>
          <div class="trajectory-main">
            <div class="traj-table" @scroll="onTrajScroll">
              <div class="traj-viewport" :style="{ height: `${trajTotal}px` }">
                <div
                  class="traj-window"
                  :style="{ transform: `translateY(${trajWindow.offset}px)` }"
                >
                  <template v-for="row in trajWindow.rows" :key="row.key">
                    <header v-if="row.node === null" class="traj-turn">
                      <span>第 {{ row.turn.index }} 轮</span>
                      <span class="traj-turn-time">{{ row.turn.startedAt }}</span>
                      <span class="traj-status" :data-status="row.turn.status">{{
                        row.turn.statusLabel
                      }}</span>
                      <span class="traj-turn-duration">{{ row.turn.duration }}</span>
                    </header>
                    <button
                      v-else
                      type="button"
                      class="traj-row"
                      :class="{ 'is-selected': selected?.key === row.node?.key }"
                      @click="selectTrajRow(row)"
                    >
                      <span class="traj-time">{{ row.node?.time }}</span>
                      <span class="traj-badge" :data-kind="row.node?.kind">{{
                        row.node?.badge
                      }}</span>
                      <span class="traj-title">{{ row.node?.title }}</span>
                      <span class="traj-duration">{{ row.node?.duration ?? '' }}</span>
                    </button>
                  </template>
                </div>
              </div>
            </div>
            <aside class="traj-inspector">
              <template v-if="selected">
                <div class="inspector-head">
                  <span class="traj-badge" :data-kind="selected.kind">{{ selected.badge }}</span>
                  <span class="inspector-title">{{ selected.title }}</span>
                </div>
                <p class="inspector-meta">
                  {{ selected.time }}
                  <template v-if="selected.duration"> · 耗时 {{ selected.duration }}</template>
                </p>
                <p v-if="selected.meta" class="inspector-meta">{{ selected.meta }}</p>
                <pre v-if="selected.detail" class="inspector-body">{{ selected.detail }}</pre>
                <p v-else class="inspector-meta">没有更多细节。</p>
              </template>
              <p v-else class="trajectory-empty">选择左侧一条记录查看详情。</p>
            </aside>
          </div>
        </template>
      </div>
    </el-drawer>

    <el-drawer v-model="subVisible" title="后台任务" size="46%">
      <div class="subs">
        <p class="subs-hint">
          子会话只完成给定任务：干净上下文（无人格、无记忆），权限沿用本会话，审批自动拒绝。
        </p>
        <p v-if="subSessions.length === 0" class="trajectory-empty">还没有后台任务。</p>
        <div v-for="item in subSessions" :key="item.id" class="sub-item">
          <div class="sub-head">
            <span class="sub-title">{{ item.title || '未命名任务' }}</span>
            <el-tag :type="subTagType(item.status)" effect="plain" size="small">
              {{ subStatusLabel(item.status) }}
            </el-tag>
            <span class="sub-time">{{ formatTime(item.created_at) }}</span>
          </div>
          <p class="sub-task">{{ item.task }}</p>
          <pre v-if="item.result" class="sub-result">{{ item.result }}</pre>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';

import {
  addAssistantSession,
  addSubSession,
  addTurnResume,
  addTurnStream,
  addWorkspace,
  deleteAssistantSession,
  deleteWorkspace,
  getAssistantSessions,
  getSessionCalls,
  getSessionEvents,
  getSessionUsage,
  getSubSessions,
  getWorkspaceDirs,
  getWorkspaces,
  updateAssistantSession,
  updateSessionPermission,
  updateTurnStop,
  updateWorkspace,
} from '@/api/assistant';
import type {
  AssistantSession,
  DirEntry,
  ModelCallItem,
  PermissionPreset,
  SessionEventItem,
  SessionUsage,
  SubSessionItem,
  Workspace,
} from '@/types/assistant';
import { formatDateTime } from '@/utils/date';
import MarkdownView from '@/components/assistant/MarkdownView.vue';
import ToolIcon from '@/components/assistant/ToolIcon.vue';

/** 会话分组：一个工作区一组（无工作区的旧会话按目录兜底成组） */
interface SessionGroup {
  key: string;
  name: string;
  path: string;
  workspaceId: string | null;
  items: AssistantSession[];
}

/** 工具调用行：名称、参数与结果 */
interface ToolLine {
  name: string;
  args: string;
  result?: string;
  error?: string;
  /** 调用开始时间与耗时（毫秒） */
  startedAt?: number;
  duration?: string;
}

/** 待审批信息：request_id 用于恢复重放 */
interface PermissionInfo {
  request_id: string;
  name: string;
  reason: string;
}

/** 消息：用户或可乐；可乐可带思考、工具与审批卡 */
interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  thinking: string;
  tools: ToolLine[];
  permission?: PermissionInfo;
}

const sessions = ref<AssistantSession[]>([]);
const currentId = ref<string>('');
const current = ref<AssistantSession | null>(null);
/** 草稿会话：新建工作区/点「＋」时自动创建，未发消息就切走即丢弃（对齐 DSH） */
const draftSessionId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
/** 上一步以工具调用收尾：下一个增量事件开启新的步骤块，保证"思考 → 工具 → 思考"的循环可见 */
let awaitingNextStep = false;
const input = ref('');
const running = ref(false);
/** 过程组展开状态：完成的轮次默认折叠，运行中的轮次强制展开 */
const expandedTurns = ref<Set<string>>(new Set());
const stepLabel = ref('1');
const personaLabel = ref('v1');
const messageBox = ref<HTMLElement | null>(null);
const workspaceDialogVisible = ref(false);
const workspacePath = ref('');
const workspaces = ref<Workspace[]>([]);
const searchVisible = ref(false);

/** 会话栏展开状态（持久化） */
const sessionsOpen = ref(localStorage.getItem('assistant.sessionsOpen') !== '0');

/** 切换中：先淡出，改布局，再淡入，避免正文在动画里逐帧重排 */
const switching = ref(false);

/** 扫描擦除层 */
const wipeEl = ref<HTMLElement | null>(null);

/** 亮网格扫描：崩解时亮网格从左到右被擦掉，重组时从右到左重绘 */
function runWipe(mode: 'break' | 'assemble'): void {
  const el = wipeEl.value;
  if (!el) {
    return;
  }
  const edge = el.querySelector('.wipe-edge');
  const shrink = [{ clipPath: 'inset(0 0 0 0%)' }, { clipPath: 'inset(0 0 0 100%)' }];
  const grow = [{ clipPath: 'inset(0 0 0 100%)' }, { clipPath: 'inset(0 0 0 0%)' }];
  const edgeMove =
    mode === 'break' ? [{ left: '0%' }, { left: '100%' }] : [{ left: '100%' }, { left: '0%' }];
  el.animate(mode === 'break' ? shrink : grow, {
    duration: 800,
    easing: 'linear',
    fill: 'forwards',
  });
  edge?.animate(edgeMove, { duration: 800, easing: 'linear', fill: 'forwards' });
}

/** 收起 / 展开会话栏：淡出 + 网格扫描 → 换布局 → 淡入 */
function toggleSessions(): void {
  if (switching.value) {
    return;
  }
  switching.value = true;
  runWipe(sessionsOpen.value ? 'break' : 'assemble');
  window.setTimeout(() => {
    sessionsOpen.value = !sessionsOpen.value;
    localStorage.setItem('assistant.sessionsOpen', sessionsOpen.value ? '1' : '0');
    window.requestAnimationFrame(() => {
      switching.value = false;
    });
  }, 850);
}
const workspaceKeyword = ref('');
const onlyWithSessions = ref(false);
const usage = ref<SessionUsage | null>(null);
const trajectoryVisible = ref(false);
const trajectoryEvents = ref<SessionEventItem[]>([]);
const modelCalls = ref<ModelCallItem[]>([]);
const subVisible = ref(false);
const subSessions = ref<SubSessionItem[]>([]);
const collapsedGroups = ref<Record<string, boolean>>({});
const dirDialogVisible = ref(false);
const dirPath = ref('');
const dirParent = ref<string | null>(null);
const dirEntries = ref<DirEntry[]>([]);
let subTimer: number | null = null;

/** 工作区分组：已有工作区（含空工作区）+ 旧会话按目录兜底分组 */
const sessionGroups = computed<SessionGroup[]>(() => {
  const groups: SessionGroup[] = workspaces.value.map((workspace) => ({
    key: `ws:${workspace.id}`,
    name: workspace.name,
    path: workspace.path,
    workspaceId: workspace.id,
    items: [],
  }));
  const byWorkspace = new Map(groups.map((group) => [group.workspaceId, group]));
  const legacyGroups = new Map<string, SessionGroup>();
  for (const item of sessions.value) {
    const workspaceGroup = item.workspace_id ? byWorkspace.get(item.workspace_id) : undefined;
    if (workspaceGroup) {
      workspaceGroup.items.push(item);
      continue;
    }
    const key = `dir:${item.work_dir}`;
    const legacy = legacyGroups.get(key) ?? {
      key,
      name: baseName(item.work_dir),
      path: item.work_dir,
      workspaceId: null,
      items: [],
    };
    legacy.items.push(item);
    legacyGroups.set(key, legacy);
  }
  const all = [...groups, ...legacyGroups.values()];
  for (const group of all) {
    group.items.sort((left, right) => right.updated_at.localeCompare(left.updated_at));
  }
  const keyword = workspaceKeyword.value.trim().toLowerCase();
  return all.filter((group) => {
    if (onlyWithSessions.value && group.items.length === 0) {
      return false;
    }
    if (!keyword) {
      return true;
    }
    return group.name.toLowerCase().includes(keyword) || group.path.toLowerCase().includes(keyword);
  });
});

/** 最近使用的工作区/目录（新增工作区快捷选择） */
const recentDirs = computed(() =>
  [
    ...new Set([
      ...workspaces.value.map((workspace) => workspace.path),
      ...sessions.value.map((item) => item.work_dir),
    ]),
  ].slice(0, 6),
);

/** 目录名：取路径最后一段 */
function baseName(path: string): string {
  const parts = path.split(/[\\/]/).filter((part) => part.length > 0);
  return parts.at(-1) ?? path;
}

/** 展开/收起分组 */
function toggleGroup(key: string): void {
  collapsedGroups.value = { ...collapsedGroups.value, [key]: !collapsedGroups.value[key] };
}

/** 打开目录选择器：优先定位当前输入目录，否则列盘符 */
async function openDirPicker(): Promise<void> {
  dirDialogVisible.value = true;
  await loadDir(workspacePath.value.trim() || undefined);
}

/** 载入目录：不传 path 时列盘符 */
async function loadDir(path?: string): Promise<void> {
  const listing = await getWorkspaceDirs(path);
  dirPath.value = listing.path;
  dirParent.value = listing.parent;
  dirEntries.value = listing.entries;
}

/** 进入子目录 */
async function enterDir(path: string): Promise<void> {
  await loadDir(path);
}

/** 返回上一级 */
async function goUpDir(): Promise<void> {
  await loadDir(dirParent.value ?? undefined);
}

/** 确认选择当前目录 */
function chooseCurrentDir(): void {
  if (!dirPath.value) {
    return;
  }
  workspacePath.value = dirPath.value;
  dirDialogVisible.value = false;
}

/** 重命名会话 */
async function handleRename(item: AssistantSession): Promise<void> {
  let value = '';
  try {
    const result = await ElMessageBox.prompt('输入新的会话标题', '重命名', {
      inputValue: item.title,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    value = result.value.trim();
  } catch {
    return;
  }
  if (!value) {
    return;
  }
  await updateAssistantSession(item.id, value);
  if (draftSessionId.value === item.id) {
    draftSessionId.value = null;
  }
  item.title = value;
  if (currentId.value === item.id && current.value) {
    current.value = { ...current.value, title: value };
  }
}

/** 删除工作区：其下会话一并逻辑删除，磁盘目录不动 */
async function handleDeleteWorkspace(group: SessionGroup): Promise<void> {
  const workspaceId = group.workspaceId;
  if (!workspaceId) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认删除工作区「${group.name}」？其下会话记录会一并删除`,
      '删除工作区',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    );
  } catch {
    return;
  }
  await deleteWorkspace(workspaceId);
  const deletedIds = new Set(group.items.map((item) => item.id));
  if (draftSessionId.value && deletedIds.has(draftSessionId.value)) {
    draftSessionId.value = null;
  }
  if (current.value && deletedIds.has(current.value.id)) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
  await loadWorkspaces();
  await loadSessions();
  ElMessage.success('工作区已删除');
}

/** 删除单条会话：只删记录，磁盘文件不动 */
async function handleDeleteSession(item: AssistantSession): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除会话「${item.title || '未命名会话'}」？`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  await deleteAssistantSession(item.id);
  sessions.value = sessions.value.filter((row) => row.id !== item.id);
  if (draftSessionId.value === item.id) {
    draftSessionId.value = null;
  }
  if (currentId.value === item.id) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
}

/** 运行中的后台任务数（抽屉按钮角标） */
const runningSubCount = computed(
  () => subSessions.value.filter((item) => item.status === 'running').length,
);

/** 会话总 token：输入 + 输出 */
const totalTokens = computed(
  () => (usage.value?.prompt_tokens ?? 0) + (usage.value?.completion_tokens ?? 0),
);

/** 前缀缓存命中率：命中 /（命中 + 未命中）输入 token；没有数据时为 null */
const cacheRate = computed(() => {
  const current = usage.value;
  if (!current) {
    return null;
  }
  const total = current.prompt_cache_hit_tokens + current.prompt_cache_miss_tokens;
  return total > 0 ? Math.round((current.prompt_cache_hit_tokens / total) * 100) : null;
});

let abortController: AbortController | null = null;

/** 后台任务状态中文名 */
function subStatusLabel(status: string): string {
  if (status === 'running') {
    return '运行中';
  }
  if (status === 'completed') {
    return '已完成';
  }
  return '失败';
}

/** 后台任务状态标签色 */
function subTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === 'running') {
    return 'info';
  }
  return status === 'completed' ? 'success' : 'danger';
}

/** 权限档位选项（对齐 DSH：能力级三档，上下排列） */
const PRESET_OPTIONS: { value: PermissionPreset; label: string }[] = [
  { value: 'read-only', label: '仅可查看' },
  { value: 'workspace-write', label: '工作区内修改' },
  { value: 'full-access', label: '完全权限' },
];

/** 当前会话的规范化档位（兼容旧值） */
const currentPreset = computed<PermissionPreset>(() => {
  const value = current.value?.permission_preset ?? 'workspace-write';
  if (value === 'loose' || value === 'standard') {
    return 'workspace-write';
  }
  if (value === 'strict') {
    return 'read-only';
  }
  return value as PermissionPreset;
});

/** 档位中文名（对齐 DSH：仅可查看 / 工作区内修改 / 完全权限；兼容旧值） */
function presetLabel(preset: string): string {
  if (preset === 'read-only' || preset === 'strict') {
    return '仅可查看';
  }
  if (preset === 'full-access') {
    return '完全权限';
  }
  return '工作区内修改';
}

/** 时间格式化：统一走工具函数 */
function formatTime(value: string): string {
  return formatDateTime(value);
}

/** 载入侧栏：工作区 + 会话；首次自动选中最近会话 */
async function loadSidebar(): Promise<void> {
  const [sessionList, workspaceList] = await Promise.all([getAssistantSessions(), getWorkspaces()]);
  sessions.value = sessionList;
  workspaces.value = workspaceList;
  const first = sessions.value.at(0);
  if (!currentId.value && first !== undefined) {
    await selectSession(first);
  }
}

/** 只刷新会话（回合结束、标题生成后）：无标题的草稿不进入列表 */
async function loadSessions(): Promise<void> {
  sessions.value = await visibleSessions();
}

/** 会话列表剔除草稿：无标题且不是当前会话的一律不展示 */
async function visibleSessions(): Promise<AssistantSession[]> {
  const list = await getAssistantSessions();
  return list.filter((item) => item.title.trim() !== '' || item.id === currentId.value);
}

/** 只刷新工作区 */
async function loadWorkspaces(): Promise<void> {
  workspaces.value = await getWorkspaces();
}

/** 选择会话：重放事件重建消息；先丢弃未使用的草稿会话 */
async function selectSession(item: AssistantSession): Promise<void> {
  if (draftSessionId.value && draftSessionId.value !== item.id) {
    await discardDraft();
  }
  currentId.value = item.id;
  current.value = item;
  messages.value = [];
  const [events, calls] = await Promise.all([getSessionEvents(item.id), getSessionCalls(item.id)]);
  trajectoryEvents.value = events;
  modelCalls.value = calls;
  applyReplay(events);
  await loadUsage();
  await loadSubSessions();
  if (subVisible.value) {
    startSubPolling();
  }
  await scrollToBottom();
}

/** 刷新会话用量 */
async function loadUsage(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  usage.value = await getSessionUsage(currentId.value);
}

/** 打开轨迹抽屉：按最新事件与调用明细刷新 */
async function openTrajectory(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  [trajectoryEvents.value, modelCalls.value] = await Promise.all([
    getSessionEvents(currentId.value),
    getSessionCalls(currentId.value),
  ]);
  trajectoryVisible.value = true;
}

// 活动时间线：按轮次分组，只保留语义节点（文本/思考增量不单独成条）
interface TrailNode {
  key: string;
  kind: 'user' | 'assistant' | 'tool' | 'call' | 'error';
  badge: string;
  title: string;
  detail?: string;
  time: string;
  /** 工具/步骤耗时（开始到结果的时间差） */
  duration?: string;
  startedMs?: number;
  endedMs?: number;
  /** 检查器附加信息：模型、token 等 */
  meta?: string;
}
interface TrailTurn {
  key: string;
  index: number;
  startedAt: string;
  startedMs: number;
  endedMs: number | null;
  status: string;
  statusLabel: string;
  duration: string;
  nodes: TrailNode[];
  /** 时间概览用的轮次时间窗 */
  spanStart: number;
  spanEnd: number;
}

/** 新轮次：turn/start 建一个分组 */
function newTrailTurn(index: number, event: SessionEventItem): TrailTurn {
  const at = Date.parse(event.created_at);
  return {
    key: `turn-${String(event.seq)}`,
    index,
    startedAt: formatClock(event.created_at),
    startedMs: Number.isNaN(at) ? 0 : at,
    endedMs: null,
    status: 'running',
    statusLabel: '进行中',
    duration: '',
    nodes: [],
    spanStart: Number.isNaN(at) ? 0 : at,
    spanEnd: Number.isNaN(at) ? 0 : at,
  };
}

/** 单事件归并为节点：文本/思考增量直接跳过 */
function appendTrailEvent(turn: TrailTurn, event: SessionEventItem): void {
  const time = formatClock(event.created_at);
  const at = Date.parse(event.created_at);
  if (event.type === 'user/message') {
    const content = String(event.payload['content'] ?? '');
    turn.nodes.push({
      key: `e${String(event.seq)}`,
      kind: 'user',
      badge: '你',
      title: preview(content),
      detail: content,
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'assistant/message') {
    const content = String(event.payload['content'] ?? '').trim();
    if (!content) {
      return;
    }
    turn.nodes.push({
      key: `a${String(event.seq)}`,
      kind: 'assistant',
      badge: '助手',
      title: preview(content),
      detail: content,
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'tool.call') {
    const name = String(event.payload['name'] ?? '');
    const args = String(event.payload['arguments'] ?? '');
    turn.nodes.push({
      key: `t${String(event.seq)}`,
      kind: 'tool',
      badge: '工具',
      title: `${name} ${preview(args, 80)}`,
      detail: prettyJson(args),
      time,
      startedMs: at,
    });
    return;
  }
  if (event.type === 'tool.result') {
    const name = String(event.payload['name'] ?? '');
    const node = [...turn.nodes]
      .reverse()
      .find((item) => item.kind === 'tool' && item.title.startsWith(name));
    if (!node) {
      return;
    }
    const error = event.payload['error'];
    node.endedMs = at;
    node.duration = durationLabel(node.startedMs ?? at, at);
    if (error) {
      node.kind = 'error';
      node.badge = '失败';
      node.title = `${name} 失败：${preview(String(error), 100)}`;
      node.detail = String(error);
      return;
    }
    node.title = `${node.title} → ${preview(String(event.payload['result'] ?? ''), 80)}`;
    return;
  }
  if (event.type === 'error') {
    const message = String(event.payload['message'] ?? '');
    turn.nodes.push({
      key: `x${String(event.seq)}`,
      kind: 'error',
      badge: '错误',
      title: preview(message, 120),
      detail: message,
      time,
      startedMs: at,
      endedMs: at,
    });
    return;
  }
  if (event.type === 'turn.end') {
    turn.status = String(event.payload['status'] ?? 'completed');
    turn.statusLabel = trailStatusLabel(turn.status);
    const at = Date.parse(event.created_at);
    turn.endedMs = Number.isNaN(at) ? null : at;
    turn.duration = durationLabel(turn.startedMs, turn.endedMs);
  }
}

/** 模型调用按发生时间归入对应轮次（调用明细没有 turn_seq） */
function attachModelCalls(turns: TrailTurn[], calls: ModelCallItem[]): void {
  for (const call of calls) {
    const at = Date.parse(call.created_at);
    if (Number.isNaN(at)) {
      continue;
    }
    const turn = turns.find(
      (item) => at >= item.startedMs && (item.endedMs === null || at <= item.endedMs),
    );
    if (!turn) {
      continue;
    }
    const latency = call.latency_ms === null ? '' : ` · ${(call.latency_ms / 1000).toFixed(1)}s`;
    const tokens =
      call.prompt_tokens === null
        ? ''
        : ` · ${String(call.prompt_tokens)}+${String(call.completion_tokens ?? 0)} tok`;
    const failed = call.error_code === null ? '' : ` · 错误 ${String(call.error_code)}`;
    turn.nodes.push({
      key: `call-${call.created_at}-${call.model}`,
      kind: 'call',
      badge: '模型',
      title: `${call.model}${latency}${tokens}${failed}`,
      time: formatClock(call.created_at),
      startedMs: call.latency_ms === null ? at : at - call.latency_ms,
      endedMs: at,
      meta: `模型 ${call.model}${latency}${tokens}${failed}`,
    });
  }
}

/** 活动时间线：事件 + 调用明细归并成"轮次 → 节点" */
const trailTurns = computed<TrailTurn[]>(() => {
  const turns: TrailTurn[] = [];
  let current: TrailTurn | null = null;
  for (const event of trajectoryEvents.value) {
    if (event.type === 'turn/start') {
      current = newTrailTurn(turns.length + 1, event);
      turns.push(current);
      continue;
    }
    if (current !== null) {
      appendTrailEvent(current, event);
    }
  }
  attachModelCalls(turns, modelCalls.value);
  for (const turn of turns) {
    const starts = turn.nodes
      .map((node) => node.startedMs)
      .filter((value): value is number => value !== undefined);
    const ends = turn.nodes
      .map((node) => node.endedMs ?? node.startedMs)
      .filter((value): value is number => value !== undefined);
    turn.spanStart = starts.length > 0 ? Math.min(...starts) : turn.startedMs;
    turn.spanEnd = ends.length > 0 ? Math.max(...ends) : turn.startedMs;
  }
  return turns;
});

/** 被重试淘汰的回合 seq：重试轮会隐藏旧回合（旧答复只留在轨迹里） */
const supersededTurns = computed<Set<number>>(() => {
  const superseded = new Set<number>();
  for (const event of trajectoryEvents.value) {
    if (event.type !== 'turn/start') {
      continue;
    }
    const retryOf = event.payload['retry_of'];
    if (typeof retryOf === 'number') {
      superseded.add(retryOf);
    }
  }
  return superseded;
});

/** 选中记录：右侧检查器展示完整细节 */
const selected = ref<TrailNode | null>(null);

/** 选中一条记录 */
function selectNode(node: TrailNode): void {
  selected.value = node;
}

/** 全量时间窗：概览默认显示整段会话 */
const sessionSpan = computed<{ start: number; end: number }>(() => {
  const turns = trailTurns.value;
  if (turns.length === 0) {
    return { start: 0, end: 1 };
  }
  return {
    start: Math.min(...turns.map((turn) => turn.spanStart)),
    end: Math.max(...turns.map((turn) => turn.spanEnd)),
  };
});

/** 当前可视时间窗：null 表示全量 */
const trailDomain = ref<{ start: number; end: number } | null>(null);
/** 框选区间：非空时记录表只保留与该区间有交集的节点 */
const trailSelection = ref<{ start: number; end: number } | null>(null);

/** 时间轴当前域 */
function activeDomain(): { start: number; end: number } {
  return trailDomain.value ?? sessionSpan.value;
}

/** 节点在时间轴上的位置百分比 */
function spanStyle(node: TrailNode): Record<string, string> {
  const domain = activeDomain();
  const span = Math.max(1, domain.end - domain.start);
  const start = node.startedMs ?? domain.start;
  const end = node.endedMs ?? node.startedMs ?? start;
  return {
    left: `${Math.min(100, Math.max(0, ((start - domain.start) / span) * 100))}%`,
    width: `${Math.min(100, Math.max(0.6, ((end - start) / span) * 100))}%`,
  };
}

/** 框选带位置 */
function bandStyle(selection: { start: number; end: number }): Record<string, string> {
  const domain = activeDomain();
  const span = Math.max(1, domain.end - domain.start);
  return {
    left: `${Math.min(100, Math.max(0, ((selection.start - domain.start) / span) * 100))}%`,
    width: `${Math.min(100, Math.max(0.6, ((selection.end - selection.start) / span) * 100))}%`,
  };
}

/** 轨迹默认渲染最近 50 轮，避免超长会话铺满 DOM */
const trailLimit = ref(50);
const pagedTrailTurns = computed(() => trailTurns.value.slice(-trailLimit.value));
const hiddenTrailTurns = computed(() =>
  Math.max(0, trailTurns.value.length - pagedTrailTurns.value.length),
);

/** 加载更早的轨迹轮次（每次 50 轮） */
function showEarlierTrail(): void {
  trailLimit.value += 50;
}

/** 轨迹表格虚拟滚动：固定行高，只渲染可视窗口附近的记录 */
const TRAJ_HEADER_HEIGHT = 30;
const TRAJ_ROW_HEIGHT = 28;
const TRAJ_VIEWPORT = 560;

interface TrajRow {
  key: string;
  height: number;
  turn: TrailTurn;
  node: TrailNode | null;
}

const trajRows = computed<TrajRow[]>(() => {
  const rows: TrajRow[] = [];
  for (const turn of visibleTurns.value) {
    rows.push({ key: `${turn.key}-head`, height: TRAJ_HEADER_HEIGHT, turn, node: null });
    for (const node of turn.nodes) {
      rows.push({ key: node.key, height: TRAJ_ROW_HEIGHT, turn, node });
    }
  }
  return rows;
});

const trajOffsets = computed<number[]>(() => {
  const offsets = [0];
  for (const row of trajRows.value) {
    offsets.push((offsets.at(-1) ?? 0) + row.height);
  }
  return offsets;
});

const trajTotal = computed(() => trajOffsets.value.at(-1) ?? 0);
const trajScrollTop = ref(0);

/** 表格滚动：只更新窗口起点，不重排全部行 */
function onTrajScroll(event: Event): void {
  trajScrollTop.value = (event.target as HTMLElement).scrollTop;
}

const trajWindow = computed(() => {
  const rows = trajRows.value;
  const offsets = trajOffsets.value;
  const top = Math.max(0, trajScrollTop.value - 200);
  const bottom = trajScrollTop.value + TRAJ_VIEWPORT + 200;
  let start = 0;
  while (start < rows.length && (offsets[start + 1] ?? 0) <= top) {
    start += 1;
  }
  let end = start;
  while (end < rows.length && (offsets[end] ?? 0) < bottom) {
    end += 1;
  }
  return { rows: rows.slice(start, end), offset: offsets[start] ?? 0 };
});

/** 点击虚拟行：标题行不可选 */
function selectTrajRow(row: TrajRow): void {
  if (row.node !== null) {
    selectNode(row.node);
  }
}

/** 记录表按框选过滤 */
const visibleTurns = computed<TrailTurn[]>(() => {
  const selection = trailSelection.value;
  const base = pagedTrailTurns.value;
  if (selection === null) {
    return base;
  }
  return base
    .map((turn) => ({
      ...turn,
      nodes: turn.nodes.filter((node) => {
        const start = node.startedMs ?? node.endedMs ?? 0;
        const end = node.endedMs ?? node.startedMs ?? start;
        return end >= selection.start && start <= selection.end;
      }),
    }))
    .filter((turn) => turn.nodes.length > 0);
});

let dragAnchor: number | null = null;

/** 鼠标位置 → 时间 */
function timeAt(event: MouseEvent): number {
  const root = event.currentTarget as HTMLElement;
  const track = root.querySelector<HTMLElement>('.overview-track') ?? root;
  const rect = track.getBoundingClientRect();
  const ratio = Math.min(1, Math.max(0, (event.clientX - rect.left) / Math.max(1, rect.width)));
  const domain = activeDomain();
  return domain.start + ratio * (domain.end - domain.start);
}

function onOverviewDown(event: MouseEvent): void {
  dragAnchor = timeAt(event);
  trailSelection.value = null;
}

function onOverviewMove(event: MouseEvent): void {
  if (dragAnchor === null) {
    return;
  }
  const now = timeAt(event);
  trailSelection.value = { start: Math.min(dragAnchor, now), end: Math.max(dragAnchor, now) };
}

function onOverviewUp(): void {
  dragAnchor = null;
  const selection = trailSelection.value;
  if (selection !== null && selection.end - selection.start < 500) {
    trailSelection.value = null;
  }
}

/** 滚轮缩放时间域（以指针为锚点） */
function onOverviewWheel(event: WheelEvent): void {
  const full = sessionSpan.value;
  const current = trailDomain.value ?? full;
  const span = Math.max(1, current.end - current.start);
  const factor = event.deltaY < 0 ? 0.8 : 1.25;
  const target = Math.max(1000, Math.min(full.end - full.start, span * factor));
  const anchor = timeAt(event);
  const ratio = (anchor - current.start) / span;
  let start = anchor - target * ratio;
  let end = start + target;
  if (start < full.start) {
    start = full.start;
    end = start + target;
  }
  if (end > full.end) {
    end = full.end;
    start = end - target;
  }
  trailDomain.value = target >= full.end - full.start - 1 ? null : { start, end };
}

const TICK_STEPS = [
  1000, 2000, 5000, 10000, 15000, 30000, 60000, 120000, 300000, 600000, 900000, 1800000, 3600000,
];

/** 刻度间隔：取能让刻度数不超过 8 的最小档 */
function pickTickStep(span: number): number {
  return TICK_STEPS.find((step) => span / step <= 8) ?? 3600000;
}

/** 时间刻度：按当前域自适应间隔 */
const rulerTicks = computed<{ key: number; left: string; label: string }[]>(() => {
  const domain = activeDomain();
  const span = Math.max(1, domain.end - domain.start);
  const step = pickTickStep(span);
  const ticks: { key: number; left: string; label: string }[] = [];
  for (
    let at = Math.ceil(domain.start / step) * step;
    at <= domain.end && ticks.length < 12;
    at += step
  ) {
    ticks.push({
      key: at,
      left: `${((at - domain.start) / span) * 100}%`,
      label: formatClock(new Date(at).toISOString()),
    });
  }
  return ticks;
});

/** 以中心缩放时间域（按钮可达，不依赖滚轮） */
function zoomTrail(factor: number): void {
  const full = sessionSpan.value;
  const current = trailDomain.value ?? full;
  const span = Math.max(1, current.end - current.start);
  const target = Math.max(1000, Math.min(full.end - full.start, span * factor));
  const center = (current.start + current.end) / 2;
  let start = center - target / 2;
  let end = center + target / 2;
  if (start < full.start) {
    start = full.start;
    end = start + target;
  }
  if (end > full.end) {
    end = full.end;
    start = end - target;
  }
  trailDomain.value = target >= full.end - full.start - 1 ? null : { start, end };
}

/** 右键清除框选与缩放 */
function clearTrailView(): void {
  trailSelection.value = null;
  trailDomain.value = null;
}

/** 单行摘要：折叠空白并截断 */
function preview(text: string, limit = 120): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  return flat.length > limit ? `${flat.slice(0, limit)}…` : flat;
}

/** 参数格式化：JSON 可解析则缩进，否则原样 */
function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** 时间显示：HH:mm:ss */
function formatClock(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour12: false });
}

/** 轮次状态中文名 */
function trailStatusLabel(status: string): string {
  if (status === 'completed') return '完成';
  if (status === 'pending_stop') return '已停车';
  if (status === 'aborted') return '中止';
  if (status === 'error') return '出错';
  return status;
}

/** 轮次耗时 */
function durationLabel(startMs: number, endMs: number | null): string {
  if (endMs === null || endMs < startMs) return '';
  const ms = endMs - startMs;
  if (ms < 1000) return `${String(Math.max(1, Math.round(ms)))}ms`;
  const seconds = Math.round(ms / 1000);
  if (seconds < 60) return `${String(seconds)}s`;
  return `${String(Math.floor(seconds / 60))}m${String(seconds % 60)}s`;
}

/** token 数缩写：1.2M / 760k */
function compactTokens(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1000) return `${String(Math.round(value / 1000))}k`;
  return String(value);
}

/** 从 assistant/message 载荷里还原工具调用行 */
function replayTools(payload: Record<string, unknown>): ToolLine[] {
  const calls = payload['tool_calls'];
  if (!Array.isArray(calls)) {
    return [];
  }
  const tools: ToolLine[] = [];
  for (const entry of calls as Array<Record<string, unknown>>) {
    tools.push({ name: String(entry['name'] ?? ''), args: String(entry['arguments'] ?? '') });
  }
  return tools;
}

/** 重放单条事件：用户消息、每个步骤的助手消息（含工具行）与工具结果回填 */
function applyReplayEvent(event: SessionEventItem): void {
  const payload = event.payload;
  if (event.type === 'user/message') {
    messages.value.push({
      role: 'user',
      text: String(payload['content'] ?? ''),
      thinking: '',
      tools: [],
    });
    return;
  }
  if (event.type === 'assistant/message') {
    messages.value.push({
      role: 'assistant',
      text: String(payload['content'] ?? ''),
      thinking: String(payload['reasoning_content'] ?? ''),
      tools: replayTools(payload),
    });
    return;
  }
  if (event.type === 'tool.call') {
    const name = String(payload['name'] ?? '');
    const target = [...messages.value]
      .reverse()
      .find(
        (item) =>
          item.role === 'assistant' &&
          item.tools.some((tool) => tool.name === name && tool.startedAt === undefined),
      );
    const tool = target?.tools.find((item) => item.name === name && item.startedAt === undefined);
    if (tool !== undefined) {
      tool.startedAt = Date.parse(event.created_at);
    }
    return;
  }
  if (event.type === 'tool.result') {
    const target = [...messages.value]
      .reverse()
      .find((item) => item.role === 'assistant' && item.tools.length > 0);
    if (target !== undefined) {
      applyToolResult(target, payload, Date.parse(event.created_at));
    }
    return;
  }
  const last = messages.value.at(-1);
  if (last === undefined) {
    return;
  }
  if (event.type === 'permission.required') {
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (event.type === 'permission/decided') {
    last.permission = undefined;
  }
}

/** 把事件重放成消息列表 */
function applyReplay(events: SessionEventItem[]): void {
  messages.value = [];
  awaitingNextStep = false;
  for (const event of events) {
    applyReplayEvent(event);
  }
}

// 一轮 = 过程组（各步骤的思考/文本/工具）+ 最终回复；对齐 DSH 的轮次过程折叠
interface TurnView {
  key: string;
  user: ChatMessage | null;
  steps: ChatMessage[];
  answer: ChatMessage | null;
  running: boolean;
  /** 该轮在事件日志里的 turn_seq（重试用） */
  turnSeq?: number;
}

/** 按用户消息切分轮次：最后一轮运行中；完成轮把末条无工具的助手消息作为最终回复 */
const turnViews = computed<TurnView[]>(() => {
  const views: TurnView[] = [];
  for (const message of messages.value) {
    if (message.role === 'user') {
      views.push({
        key: `turn-${String(views.length)}`,
        user: message,
        steps: [],
        answer: null,
        running: false,
      });
      continue;
    }
    const view = views.at(-1);
    if (view !== undefined) {
      view.steps.push(message);
    }
  }
  for (const [index, view] of views.entries()) {
    view.turnSeq = turnStartSeqs.value[index];
    view.running = index === views.length - 1 && running.value;
    if (view.running) {
      continue;
    }
    const answer = view.steps.at(-1);
    if (answer === undefined || answer.tools.length > 0 || answer.text.trim() === '') {
      continue;
    }
    view.steps.pop();
    if (answer.thinking) {
      view.steps.push({ role: 'assistant', text: '', thinking: answer.thinking, tools: [] });
    }
    view.answer = { ...answer, thinking: '' };
  }
  return views.filter(
    (view) => view.turnSeq === undefined || !supersededTurns.value.has(view.turnSeq),
  );
});

/** 事件里每轮的 turn_seq（与用户消息顺序一致） */
const turnStartSeqs = computed<number[]>(() =>
  trajectoryEvents.value.filter((event) => event.type === 'turn/start').map((event) => event.seq),
);

/** 会话默认渲染最近若干轮，避免超长会话一次性挂载全部节点 */
const turnLimit = ref(25);

/** 当前渲染的轮次窗口 */
const pagedTurns = computed(() => turnViews.value.slice(-turnLimit.value));

/** 被窗口截掉的更早轮次数 */
const hiddenTurns = computed(() => Math.max(0, turnViews.value.length - pagedTurns.value.length));

/** 加载更早的轮次（每次 25 轮） */
function showEarlier(): void {
  turnLimit.value += 25;
}

/** 展开/收起某一轮的过程组 */ function toggleProcess(key: string): void {
  const next = new Set(expandedTurns.value);
  if (next.has(key)) {
    next.delete(key);
  } else {
    next.add(key);
  }
  expandedTurns.value = next;
}

/** 过程组当前是否展开：运行中强制显示，其余按用户展开状态 */
function isOpen(view: TurnView): boolean {
  return view.running || expandedTurns.value.has(view.key);
}

/** 复制最终回复 */
function copyAnswer(view: TurnView): void {
  if (!view.answer) {
    return;
  }
  void navigator.clipboard.writeText(view.answer.text).then(() => {
    ElMessage.success('已复制');
  });
}

/** 单轮用量明细 */
interface TurnCall {
  model: string;
  tokens: number;
  latency: string;
  time: string;
}

/** 单轮用量汇总 */
interface TurnUsage {
  duration: string;
  tokens: number;
  prompt: number;
  completion: number;
  cacheHit: number;
  cacheMiss: number;
  models: Set<string>;
  calls: TurnCall[];
}

/** 每轮耗时、token 与用量汇总：来自事件时间窗与调用明细 */
const turnMeta = computed<Partial<Record<number, TurnUsage>>>(() => {
  const windows: { seq: number; start: number; end: number }[] = [];
  const result: Partial<Record<number, TurnUsage>> = {};
  let current: { seq: number; start: number; end: number } | null = null;
  const empty = (): TurnUsage => ({
    duration: '',
    tokens: 0,
    prompt: 0,
    completion: 0,
    cacheHit: 0,
    cacheMiss: 0,
    models: new Set<string>(),
    calls: [],
  });
  for (const event of trajectoryEvents.value) {
    if (event.type === 'turn/start') {
      current = { seq: event.seq, start: Date.parse(event.created_at), end: Number.NaN };
      windows.push(current);
      result[event.seq] = empty();
      continue;
    }
    if (event.type === 'turn.end' && current !== null) {
      current.end = Date.parse(event.created_at);
      const entry = result[current.seq] ?? empty();
      result[current.seq] = { ...entry, duration: durationLabel(current.start, current.end) };
    }
  }
  for (const call of modelCalls.value) {
    const at = Date.parse(call.created_at);
    if (Number.isNaN(at)) {
      continue;
    }
    const window = windows.find(
      (item) => at >= item.start && (Number.isNaN(item.end) || at <= item.end),
    );
    if (window === undefined) {
      continue;
    }
    const key = window.seq;
    const entry = result[key] ?? empty();
    const prompt = call.prompt_tokens ?? 0;
    const completion = call.completion_tokens ?? 0;
    const cacheHit = call.prompt_cache_hit_tokens ?? 0;
    const cacheMiss = call.prompt_cache_miss_tokens ?? 0;
    entry.models.add(call.model);
    entry.calls.push({
      model: call.model,
      tokens: prompt + completion,
      latency: call.latency_ms === null ? '' : `${(call.latency_ms / 1000).toFixed(1)}s`,
      time: formatClock(call.created_at),
    });
    result[key] = {
      ...entry,
      tokens: entry.tokens + prompt + completion,
      prompt: entry.prompt + prompt,
      completion: entry.completion + completion,
      cacheHit: entry.cacheHit + cacheHit,
      cacheMiss: entry.cacheMiss + cacheMiss,
    };
  }
  return result;
});

/** 本轮涉及的模型列表 */
function turnModels(view: TurnView): string {
  const entry = turnMeta.value[view.turnSeq ?? 0];
  if (!entry || entry.models.size === 0) {
    return '—';
  }
  return [...entry.models].join('、');
}

/** 本轮缓存命中率 */
function turnCacheRate(view: TurnView): number {
  const entry = turnMeta.value[view.turnSeq ?? 0];
  if (!entry || entry.prompt === 0) {
    return 0;
  }
  return Math.round((entry.cacheHit / entry.prompt) * 100);
}

/** 工具结果回填：按调用顺序填第一个还没有结果的工具行 */
function applyToolResult(last: ChatMessage, payload: Record<string, unknown>, at: number): void {
  const target =
    last.tools.find((tool) => tool.result === undefined && tool.error === undefined) ??
    last.tools.at(-1);
  if (target === undefined) {
    return;
  }
  target.result = String(payload['result'] ?? '');
  target.error = payload['error'] ? String(payload['error']) : undefined;
  if (target.startedAt !== undefined) {
    target.duration = durationLabel(target.startedAt, at);
  }
}

/** 应用单条实时事件：assistant/message 一步一条，带工具调用则在下一步增量时另起消息块 */
function applyEvent(type: string, payload: Record<string, unknown>): void {
  if (type === 'assistant/message') {
    const current = messages.value.at(-1);
    if (current?.role === 'assistant') {
      current.text = String(payload['content'] ?? '');
      const reasoning = payload['reasoning_content'];
      if (typeof reasoning === 'string') {
        current.thinking = reasoning;
      }
      const calls = payload['tool_calls'];
      awaitingNextStep = Array.isArray(calls) && calls.length > 0;
    }
    return;
  }
  if (awaitingNextStep && (type === 'thinking.delta' || type === 'text.delta')) {
    messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
    awaitingNextStep = false;
  }
  const last = messages.value.at(-1);
  if (last === undefined) {
    return;
  }
  if (type === 'text.delta') {
    last.text += String(payload['text'] ?? '');
  } else if (type === 'thinking.delta') {
    last.thinking += String(payload['text'] ?? '');
  } else if (type === 'tool.call') {
    last.tools.push({
      name: String(payload['name'] ?? ''),
      args: String(payload['arguments'] ?? ''),
      startedAt: Date.now(),
    });
  } else if (type === 'tool.result') {
    applyToolResult(last, payload, Date.now());
  } else if (type === 'permission.required') {
    last.permission = {
      request_id: String(payload['request_id'] ?? ''),
      name: String(payload['name'] ?? ''),
      reason: String(payload['reason'] ?? ''),
    };
  } else if (type === 'permission/decided') {
    last.permission = undefined;
  } else if (type === 'error') {
    last.text += `\n[错误] ${String(payload['msg'] ?? payload['code'] ?? '')}`;
  } else if (type === 'turn.end' && String(payload['status'] ?? '') === 'pending_stop') {
    last.text += '\n（等待你的裁决）';
  }
  void scrollToBottom();
}

/** 滚动到底部 */
async function scrollToBottom(): Promise<void> {
  await nextTick();
  const box = messageBox.value;
  if (box) {
    box.scrollTop = box.scrollHeight;
  }
}

/** 发送消息：进入流式 */
function handleSend(): void {
  if (!current.value || !input.value.trim() || running.value) {
    return;
  }
  const content = input.value.trim();
  input.value = '';
  sendContent(content);
}

/** 发送指定内容：草稿转正、推入用户与助手占位、开启 SSE；retryOf 标记重试的旧回合 */
function sendContent(content: string, retryOf?: number): void {
  if (!current.value || running.value) {
    return;
  }
  // 发出首条消息即转正：不再按草稿丢弃
  draftSessionId.value = null;
  awaitingNextStep = false;
  messages.value.push({ role: 'user', text: content, thinking: '', tools: [] });
  messages.value.push({ role: 'assistant', text: '', thinking: '', tools: [] });
  running.value = true;
  abortController = addTurnStream(
    currentId.value,
    content,
    {
      onEvent: applyEvent,
      onDone: () => {
        running.value = false;
        void loadUsage();
        void Promise.all([
          getSessionEvents(currentId.value),
          getSessionCalls(currentId.value),
        ]).then(([events, calls]) => {
          trajectoryEvents.value = events;
          modelCalls.value = calls;
        });
        void refreshSessions();
      },
      onError: (message) => {
        running.value = false;
        ElMessage.error(message);
      },
    },
    retryOf,
  );
}

/** 停止：结束流并通知后端 */
async function handleStop(): Promise<void> {
  abortController?.abort();
  abortController = null;
  running.value = false;
  await updateTurnStop(currentId.value);
  await loadUsage();
}

/** 提交后台任务：当前输入内容交给子会话执行（干净上下文） */
async function handleBackground(): Promise<void> {
  if (!current.value || !input.value.trim() || running.value) {
    return;
  }
  const task = input.value.trim();
  input.value = '';
  await addSubSession(currentId.value, task);
  ElMessage.success('已转后台执行，结果可在「后台任务」查看');
  await loadSubSessions();
  subVisible.value = true;
  startSubPolling();
}

/** 打开后台任务抽屉：拉列表并开始轮询 */
async function openSubSessions(): Promise<void> {
  await loadSubSessions();
  subVisible.value = true;
  startSubPolling();
}

/** 载入后台任务列表 */
async function loadSubSessions(): Promise<void> {
  if (!currentId.value) {
    return;
  }
  subSessions.value = await getSubSessions(currentId.value);
}

/** 轮询：抽屉打开且有运行中任务时每 3 秒刷新 */
function startSubPolling(): void {
  stopSubPolling();
  subTimer = window.setInterval(() => {
    if (!subVisible.value) {
      stopSubPolling();
      return;
    }
    void loadSubSessions();
    if (runningSubCount.value === 0) {
      stopSubPolling();
    }
  }, 3000);
}

/** 停止轮询 */
function stopSubPolling(): void {
  if (subTimer !== null) {
    window.clearInterval(subTimer);
    subTimer = null;
  }
}

/** 裁决后恢复重放 */
function decidePermission(
  item: ChatMessage,
  outcome: 'allowed-once' | 'allowed-always' | 'rejected',
): void {
  const permission = item.permission;
  if (!permission) {
    return;
  }
  item.permission = undefined;
  running.value = true;
  abortController = addTurnResume(currentId.value, permission.request_id, outcome, {
    onEvent: applyEvent,
    onDone: () => {
      running.value = false;
      void loadUsage();
    },
    onError: (message) => {
      running.value = false;
      ElMessage.error(message);
    },
  });
}

/** 切换本会话权限档：完全权限需二次确认（对齐 DSH 的风险确认） */
async function changePreset(value: string | number | boolean | undefined): Promise<void> {
  if (!current.value || typeof value !== 'string') {
    return;
  }
  if (value === 'full-access') {
    try {
      await ElMessageBox.confirm(
        '启用完全权限后，智能体将减少确认步骤，并且可以直接执行更多操作，包括敏感操作、文件修改或外部命令。仅建议在你信任当前任务时使用。',
        '开启完全权限',
        { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' },
      );
    } catch {
      return;
    }
  }
  await updateSessionPermission(currentId.value, value as PermissionPreset);
  current.value = { ...current.value, permission_preset: value };
}

/** 工作区行菜单：复制路径 / 重命名 / 删除会话记录 */
async function handleGroupCommand(command: unknown, group: SessionGroup): Promise<void> {
  const action = String(command);
  if (action === 'copy') {
    try {
      await navigator.clipboard.writeText(group.path);
      ElMessage.success('路径已复制');
    } catch {
      ElMessage.error('复制失败，请手动选择路径');
    }
    return;
  }
  if (action === 'rename') {
    await handleRenameGroup(group);
    return;
  }
  if (action === 'delete-workspace') {
    await handleDeleteWorkspace(group);
  }
}

/** 重命名工作区：只改显示名，不动目录与会话归属 */
async function handleRenameGroup(group: SessionGroup): Promise<void> {
  if (!group.workspaceId) {
    return;
  }
  let value = '';
  try {
    const result = await ElMessageBox.prompt('输入新的工作区名称', '重命名工作区', {
      inputValue: group.name,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
    });
    value = result.value.trim();
  } catch {
    return;
  }
  if (!value || value === group.name) {
    return;
  }
  await updateWorkspace(group.workspaceId, value);
  await loadWorkspaces();
  ElMessage.success('工作区已重命名');
}

/** 打开新增工作区弹窗 */
function openWorkspaceDialog(): void {
  workspacePath.value = '';
  workspaceDialogVisible.value = true;
}

/** 新增工作区：目录选一次，之后会话都归属它；名称默认取目录名 */
async function handleCreateWorkspace(): Promise<void> {
  if (!workspacePath.value.trim()) {
    return;
  }
  const workspace = await addWorkspace(workspacePath.value.trim());
  workspaceDialogVisible.value = false;
  workspacePath.value = '';
  await loadWorkspaces();
  // 对齐 DSH：新建工作区后直接打开一个可聊的草稿会话
  await openDraftSession(workspace.id, workspace.path);
  ElMessage.success('工作区已创建，已打开新会话');
}

/** 在工作区下新建会话：不再选目录，直接继承工作区 */
async function handleNewSession(group: SessionGroup): Promise<void> {
  const workspaceId = group.workspaceId;
  if (!workspaceId) {
    return;
  }
  await openDraftSession(workspaceId, group.path);
}

/** 打开草稿会话：未发消息就切走会被丢弃（对齐 DSH） */
async function openDraftSession(workspaceId: string, path: string): Promise<void> {
  await discardDraft();
  const created = await addAssistantSession(path, '', workspaceId);
  sessions.value.unshift(created);
  draftSessionId.value = created.id;
  await selectSession(created);
}

/** 丢弃未使用的草稿会话：从列表移除并逻辑删除；清理失败不影响切换 */
async function discardDraft(): Promise<void> {
  const id = draftSessionId.value;
  draftSessionId.value = null;
  if (!id) {
    return;
  }
  sessions.value = sessions.value.filter((item) => item.id !== id);
  if (currentId.value === id) {
    currentId.value = '';
    current.value = null;
    messages.value = [];
    usage.value = null;
    subSessions.value = [];
  }
  try {
    await deleteAssistantSession(id);
  } catch {
    // 草稿可能已被别处删除：忽略清理失败，切换照常进行
  }
}

/** 刷新会话列表：首条消息后标题由后端自动生成，同步到列表与当前会话 */
async function refreshSessions(): Promise<void> {
  sessions.value = await visibleSessions();
  if (currentId.value) {
    const latest = sessions.value.find((item) => item.id === currentId.value);
    if (latest) {
      current.value = latest;
    }
  }
}

onMounted(async () => {
  await loadSidebar();
  if (currentId.value) {
    await loadSubSessions();
  }
});

onUnmounted(() => {
  stopSubPolling();
});
</script>

<style scoped lang="css">
.assistant-page {
  gap: 14px;
}

.sub-count {
  margin-left: 4px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--kb-primary);
  color: #06231a;
  font-size: 10px;
}

.subs {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.subs-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.sub-item {
  padding: 10px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
  background: var(--kb-surface);
}

.sub-head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.sub-title {
  font-size: 13px;
  font-weight: 600;
}

.sub-time {
  margin-left: auto;
  color: var(--kb-text-3);
  font-size: 11px;
}

.sub-task {
  margin: 6px 0;
  color: var(--kb-text-2);
  font-size: 12px;
  white-space: pre-wrap;
}

.sub-result {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-all;
}

.chat-wrap {
  position: relative;
  display: grid;
  flex: 1;
  min-height: 0;
  overflow: hidden;
  grid-template-columns: 240px minmax(0, 1fr);
  gap: 0;
}

.sessions-panel,
.chat-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
}

.sessions-panel {
  min-width: 240px;
  border-right: 1px solid var(--kb-line);
  background: linear-gradient(180deg, var(--kb-bg-1), #050a12);
  transition: opacity 0.8s ease;
}

.chat-panel {
  position: relative;
  background:
    radial-gradient(720px 260px at 85% -8%, var(--kb-tint), transparent 72%), var(--kb-bg-2);
  transition: opacity 0.8s ease;
}

/*
 * 切换会话栏宽度时，先把两侧淡出，再瞬间改布局，最后淡入。
 * 宽度是布局属性，若直接做过渡，正文会逐帧重排导致行数跳动；
 * 在不可见时完成重排，用户只看到一次干净的淡入淡出与网格崩解。
 */
.chat-wrap.switching .sessions-panel,
.chat-wrap.switching .chat-panel {
  opacity: 0;
}

.chat-wrap.sessions-collapsed {
  grid-template-columns: 0 minmax(0, 1fr);
}

.chat-wrap.sessions-collapsed .sessions-panel {
  border-right-color: transparent;
  opacity: 0;
  pointer-events: none;
}

/* 亮网格扫描层：整片 16px 网格，靠 clip-path 从左到右被擦掉 */
.grid-wipe {
  position: absolute;
  inset: 0;
  z-index: 6;
  overflow: hidden;
  background-image:
    linear-gradient(rgb(34 224 255 / 30%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(34 224 255 / 30%) 1px, transparent 1px);
  background-size: 16px 16px;
  pointer-events: none;
  will-change: clip-path;
}

.wipe-edge {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--kb-primary);
  box-shadow:
    0 0 12px var(--kb-primary),
    0 0 28px rgb(34 224 255 / 55%);
}

@media (prefers-reduced-motion: reduce) {
  .sessions-panel,
  .chat-panel {
    transition: none;
  }

  .grid-wipe {
    display: none;
  }
}

/* HUD 角标：左上 / 右下 */
.chat-panel::before,
.chat-panel::after {
  position: absolute;
  z-index: 3;
  width: 12px;
  height: 12px;
  border-style: solid;
  border-color: var(--kb-primary);
  border-width: 0;
  filter: drop-shadow(0 0 4px var(--kb-glow));
  opacity: 0.55;
  pointer-events: none;
  content: '';
}

.chat-panel::before {
  top: 8px;
  left: 8px;
  border-top-width: 1px;
  border-left-width: 1px;
}

.chat-panel::after {
  right: 8px;
  bottom: 8px;
  border-right-width: 1px;
  border-bottom-width: 1px;
}

.sessions-head {
  display: flex;
  flex: none;
  gap: 2px;
  align-items: center;
  padding: 12px 12px 12px 14px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-title {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.head-icon {
  display: inline-flex;
  flex: none;
  width: 28px;
  height: 28px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: none;
  color: var(--kb-text-3);
  cursor: pointer;
}

.head-icon:hover {
  background: rgb(34 224 255 / 8%);
  color: var(--kb-primary);
}

.head-icon-on {
  color: var(--kb-primary);
}

.workspace-search {
  flex: none;
  padding: 8px 10px;
  border-bottom: 1px solid var(--kb-line);
}

.sessions-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 6px 6px 12px;
}

/* 工作区行：34px；文件夹图标悬停/选中切换为展开箭头（DSH 同款交互） */
.group-head {
  display: flex;
  height: 44px;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 10px;
  border-radius: 8px;
  color: var(--kb-text-2);
  cursor: pointer;
  user-select: none;
}

.group-head:hover {
  background: rgb(34 224 255 / 5%);
}

.group-folder {
  display: inline-flex;
  flex: none;
  width: 20px;
  align-items: center;
  justify-content: center;
  color: var(--kb-primary);
}

.group-arrow {
  display: none;
  flex: none;
  width: 16px;
  color: var(--kb-text-3);
  font-size: 9px;
  text-align: center;
  transition: transform 150ms ease;
}

.group-arrow-open {
  transform: rotate(90deg);
}

.group-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  font-size: 16px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.group-actions {
  display: none;
  flex: none;
  align-items: center;
  gap: 2px;
}

.group-head:hover .group-actions {
  display: inline-flex;
}

.group-icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  border-radius: 5px;
  background: none;
  color: var(--kb-text-3);
  cursor: pointer;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}

.group-icon:hover {
  background: rgb(255 255 255 / 10%);
  color: var(--kb-text-1);
}

.group-add:hover {
  color: var(--kb-primary);
}

.group-empty {
  margin: 0;
  padding: 2px 8px 6px;
  color: var(--kb-text-3);
  font-size: 12px;
}

.group-items {
  display: flex;
  flex-direction: column;
  padding-left: 22px;
}

/* 会话行：名称在上、时间在下的两行结构 */
.session-item {
  position: relative;
  display: flex;
  height: auto;
  min-height: 48px;
  width: 100%;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 3px;
  padding: 8px 34px 8px 14px;
  border: none;
  clip-path: polygon(6px 0, 100% 0, 100% calc(100% - 6px), calc(100% - 6px) 100%, 0 100%, 0 6px);
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  text-align: left;
}

.session-item:hover {
  background: rgb(34 224 255 / 5%);
}

.session-item-on {
  background: linear-gradient(90deg, rgb(34 224 255 / 14%), rgb(34 224 255 / 3%) 72%, transparent);
  box-shadow: inset 0 0 0 1px var(--kb-line);
}

.session-item-on::before {
  position: absolute;
  top: 5px;
  bottom: 5px;
  left: 0;
  width: 2px;
  background: var(--kb-primary);
  box-shadow: 0 0 12px var(--kb-primary);
  content: '';
}

.session-item-on:hover {
  background: var(--kb-tint);
}

.session-title {
  flex: none;
  width: 100%;
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-time {
  display: block;
  flex: none;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  letter-spacing: 0.04em;
}

.session-rename {
  position: absolute;
  top: 6px;
  right: 22px;
  display: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-rename:hover {
  color: var(--kb-primary);
}

.session-delete {
  position: absolute;
  top: 6px;
  right: 4px;
  display: none;
  width: 18px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: center;
}

.session-delete:hover {
  color: var(--kb-danger);
}

.session-item:hover .session-rename,
.session-item:hover .session-delete {
  display: block;
}

.session-group {
  margin-bottom: 2px;
}

.workdir-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.recent-dirs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  margin-top: 8px;
}

.recent-label {
  color: var(--kb-text-3);
  font-size: 12px;
}

.dir-chip {
  max-width: 140px;
  overflow: hidden;
  padding: 2px 10px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  background: var(--kb-surface);
  color: var(--kb-text-2);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dir-chip-on {
  border-color: var(--kb-primary);
  color: var(--kb-primary);
}

.new-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 12px;
}

.dir-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.dir-path {
  flex: 1;
  overflow: hidden;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dir-list {
  display: flex;
  max-height: 320px;
  flex-direction: column;
  gap: 2px;
  overflow: auto;
  padding: 4px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.dir-empty {
  margin: 0;
  padding: 14px;
  color: var(--kb-text-3);
  font-size: 12px;
  text-align: center;
}

.dir-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: none;
  color: var(--kb-text-1);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 13px;
  text-align: left;
}

.dir-item:hover {
  background: rgb(255 255 255 / 5%);
}

.dir-icon {
  display: inline-flex;
  color: var(--kb-text-3);
}

.dir-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-empty,
.messages-empty {
  padding: 18px 12px;
  color: var(--kb-text-3);
  font-size: 13px;
  text-align: center;
}

.chat-head {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--kb-line);
}

.chat-title {
  font-size: 14px;
  font-weight: 650;
}

.chat-dir {
  padding: 4px 12px;
  border: 1px solid var(--kb-line-2);
  border-left: 2px solid var(--kb-primary);
  clip-path: polygon(7px 0, 100% 0, 100% calc(100% - 7px), calc(100% - 7px) 100%, 0 100%, 0 7px);
  background: linear-gradient(90deg, var(--kb-tint), transparent 70%);
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  letter-spacing: 0.02em;
  box-shadow: inset 0 0 14px var(--kb-tint);
}

.chat-status {
  color: var(--kb-text-3);
  font-size: 12px;
}

/* 会话轨迹：顶部时间概览 + 按轮次的事件记录表 + 右侧检查器（对齐 DSH Trajectory） */
.trajectory-shell {
  display: flex;
  flex-direction: column;
  gap: 14px;
  height: 100%;
}

.trajectory-empty {
  color: var(--kb-text-3);
  font-size: 13px;
}

.overview {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--kb-line);
  cursor: crosshair;
  user-select: none;
}

.overview-hint {
  margin: 0;
  color: var(--kb-text-3);
  font-size: 11px;
}

.overview-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
}

.overview-tools {
  display: flex;
  gap: 4px;
}

.overview-zoom {
  padding: 0 6px;
  border: 1px solid var(--kb-line);
  border-radius: 5px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
  line-height: 18px;
}

.overview-zoom:hover {
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 5%);
}

.overview-ruler {
  display: flex;
  gap: 8px;
  align-items: center;
}

.overview-ruler-track {
  position: relative;
  flex: 1;
  height: 14px;
}

.overview-tick {
  position: absolute;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 10px;
  transform: translateX(-50%);
}

.overview-band {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: 999px;
  background: rgb(96 165 250 / 22%);
  pointer-events: none;
}

.overview-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.overview-label {
  flex: none;
  width: 56px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.overview-track {
  position: relative;
  flex: 1;
  height: 10px;
  border-radius: 999px;
  background: rgb(255 255 255 / 5%);
}

.overview-span {
  position: absolute;
  top: 0;
  bottom: 0;
  min-width: 3px;
  padding: 0;
  border: none;
  border-radius: 999px;
  background: var(--kb-text-3);
  cursor: pointer;
}

.overview-span[data-kind='tool'] {
  background: var(--kb-primary-2);
}

.overview-span[data-kind='call'] {
  background: #60a5fa;
}

.overview-span[data-kind='assistant'] {
  background: #34d399;
}

.overview-span[data-kind='error'] {
  background: var(--kb-danger);
}

.overview-duration {
  flex: none;
  width: 48px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: right;
}

.trajectory-main {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 14px;
  min-height: 0;
}

.traj-table {
  max-height: 62vh;
  overflow: auto;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.traj-viewport {
  position: relative;
}

.traj-window {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
}

.traj-turn {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 10px;
  border-bottom: 1px solid var(--kb-line);
  background: var(--kb-surface);
  font-size: 12px;
}

.traj-turn-time,
.traj-turn-duration {
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.traj-status {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 6%);
  font-size: 11px;
}

.traj-status[data-status='completed'] {
  color: var(--kb-ok);
}

.traj-status[data-status='aborted'],
.traj-status[data-status='error'] {
  color: var(--kb-danger);
}

.traj-status[data-status='pending_stop'] {
  color: #f59e0b;
}

.traj-row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  width: 100%;
  padding: 6px 10px;
  border: none;
  border-bottom: 1px solid rgb(255 255 255 / 4%);
  color: var(--kb-text);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
}

.traj-row:hover {
  background: rgb(255 255 255 / 4%);
}

.traj-row.is-selected {
  background: rgb(96 165 250 / 12%);
}

.traj-time {
  flex: none;
  width: 62px;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.traj-badge {
  flex: none;
  width: 34px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.traj-badge[data-kind='tool'] {
  color: var(--kb-primary-2);
}

.traj-badge[data-kind='call'] {
  color: #60a5fa;
}

.traj-badge[data-kind='error'] {
  color: var(--kb-danger);
}

.traj-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.traj-duration {
  flex: none;
  width: 48px;
  color: var(--kb-text-3);
  font-size: 11px;
  text-align: right;
}

.traj-inspector {
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 10px;
}

.inspector-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  margin-bottom: 6px;
}

.inspector-title {
  font-weight: 600;
  font-size: 13px;
}

.inspector-meta {
  margin: 2px 0;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.inspector-body {
  max-height: 55vh;
  margin: 8px 0 0;
  padding: 10px;
  overflow: auto;
  overflow-wrap: anywhere;
  border-radius: 8px;
  color: var(--kb-text-2);
  background: rgb(0 0 0 / 30%);
  font-size: 11px;
  white-space: pre-wrap;
}

/* 轮次 = 过程组 + 最终回复；过程组默认折叠（运行中展开） */
.turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  content-visibility: auto;
  contain-intrinsic-size: auto 200px;
}

.process {
  padding-left: 14px;
  border-left: 2px solid var(--kb-line);
}

.process-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: -6px;
  padding: 4px 6px;
  border: none;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: transparent;
  cursor: pointer;
  font-size: 12px;
}

.process-head:hover {
  background: rgb(255 255 255 / 4%);
}

.process-chevron {
  width: 10px;
  color: var(--kb-text-3);
  font-size: 10px;
}

.process-title {
  font-weight: 600;
}

.process-meta {
  color: var(--kb-text-3);
  font-size: 11px;
}

.process-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.step {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.message-answer {
  margin-top: 4px;
}

.turn-earlier {
  align-self: center;
  padding: 4px 12px;
  border: 1px solid var(--kb-line);
  border-radius: 999px;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}

.turn-earlier:hover {
  color: var(--kb-text-2);
  background: rgb(255 255 255 / 4%);
}

.messages {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: 24px;
  overflow: auto;
  padding: 24px 28px 28px;
}

.message {
  display: flex;
  max-width: 100%;
}

.message-bubble {
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  line-height: 1.8;
}

.message-user {
  justify-content: flex-end;
}

.message-user .message-bubble {
  max-width: 60%;
  padding: 6px 10px;
  border: 1px solid rgb(52 211 153 / 18%);
  border-radius: 10px;
  background: rgb(52 211 153 / 8%);
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.message-assistant .message-bubble {
  width: 100%;
}

.process-head.is-running .process-title {
  background: linear-gradient(90deg, var(--kb-text-2), #34d399, var(--kb-text-2));
  background-size: 200% 100%;
  animation: process-shimmer 1.6s linear infinite;
  background-clip: text;
  color: transparent;
}

@keyframes process-shimmer {
  from {
    background-position: 200% 0;
  }

  to {
    background-position: 0 0;
  }
}

.step-label {
  color: var(--kb-text-3);
  font-size: 11px;
}

.reasoning {
  margin: 0;
}

.reasoning-head {
  display: flex;
  gap: 8px;
  align-items: baseline;
  cursor: pointer;
  list-style: none;
}

.reasoning-label {
  flex: none;
  color: var(--kb-text-3);
  font-size: 11px;
}

.reasoning-preview {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--kb-text-3);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reasoning-body {
  margin: 6px 0 4px;
  padding: 8px 10px;
  overflow-wrap: anywhere;
  border-left: 2px solid var(--kb-line-2);
  background: rgb(255 255 255 / 3%);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
}

.turn-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  padding-left: 2px;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.turn-footer:focus-within,
.turn:last-child .turn-footer,
.turn:hover .turn-footer {
  opacity: 1;
}

@media (hover: none) {
  .turn-footer {
    opacity: 1;
  }
}

.turn-meta {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0;
  border: none;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
}

.turn-meta:hover {
  color: var(--kb-text-2);
}

.turn-icon {
  width: 12px;
  height: 12px;
}

.turn-actions {
  display: flex;
  gap: 10px;
  margin-left: auto;
}

.turn-action {
  padding: 0;
  border: none;
  color: var(--kb-text-3);
  background: transparent;
  cursor: pointer;
  font-size: 11px;
}

.turn-action:hover {
  color: var(--kb-primary-2);
}

.usage-pop {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.usage-pop-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--kb-line);
}

.usage-pop-title {
  color: var(--kb-text-2);
  font-size: 12px;
  font-weight: 600;
}

.usage-pop-total {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
}

.usage-pop-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.usage-pop-row > :last-child {
  color: var(--kb-text-2);
  font-family: ui-monospace, Consolas, monospace;
}

.tool-card {
  margin: 2px 0;
  border: none;
  border-radius: 6px;
  background: transparent;
}

.tool-head {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-left: -6px;
  padding: 3px 6px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  list-style: none;
}

.tool-head:hover {
  background: rgb(255 255 255 / 4%);
}

.tool-icon {
  flex: none;
  width: 12px;
  height: 12px;
  color: var(--kb-text-3);
}

.tool-name {
  color: var(--kb-primary-2);
  font-family: ui-monospace, Consolas, monospace;
}

.tool-args {
  overflow: hidden;
  color: var(--kb-text-3);
  font-family: ui-monospace, Consolas, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-state {
  margin-left: auto;
  flex: none;
  color: var(--kb-text-3);
  font-size: 11px;
}

.tool-card.tool-fail .tool-state {
  color: var(--kb-danger);
}

.tool-body {
  margin-left: 12px;
  padding: 2px 0 8px;
  border-top: 1px solid var(--kb-line);
}

.tool-label {
  margin: 8px 0 4px;
  color: var(--kb-text-3);
  font-size: 11px;
}

.tool-pre {
  max-height: 260px;
  margin: 0;
  padding: 8px 10px;
  overflow: auto;
  overflow-wrap: anywhere;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: rgb(0 0 0 / 30%);
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  white-space: pre-wrap;
}

.tool-error {
  color: var(--kb-danger);
}

.message-perm {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid rgb(251 191 36 / 40%);
  border-radius: 12px;
  background: rgb(251 191 36 / 8%);
}

.perm-title {
  color: var(--kb-warn);
  font-size: 13px;
  font-weight: 600;
}

.perm-reason {
  margin: 6px 0 10px;
  color: var(--kb-text-2);
  font-size: 13px;
}

.perm-actions {
  display: flex;
  gap: 8px;
}

.composer {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px 14px;
  border-top: 1px solid var(--kb-line);
}

.composer-row {
  display: flex;
  gap: 10px;
  align-items: center;
}

.composer-hints {
  display: flex;
  flex: 1;
  gap: 14px;
  align-items: center;
  color: var(--kb-text-3);
  font-size: 12px;
}

.hint-link {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0;
  border: none;
  background: none;
  color: var(--kb-primary);
  cursor: pointer;
  font-family: 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  font-size: 12px;
}

.hint-icon {
  width: 12px;
  height: 12px;
}

.preset-box {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.preset-item {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 8px;
  border: none;
  border-radius: 6px;
  color: var(--kb-text-2);
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-size: 12px;
}

.preset-item:hover {
  background: rgb(255 255 255 / 5%);
}

.preset-item-on {
  background: rgb(52 211 153 / 10%);
}

.preset-name {
  flex: 1;
}

.preset-check {
  color: var(--kb-primary-2);
  font-size: 12px;
}
</style>
