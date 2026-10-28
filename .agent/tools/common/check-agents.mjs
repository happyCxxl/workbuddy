#!/usr/bin/env node
// AGENTS 入口体检：入口文件反引号里引用的仓库路径必须真实存在（指针失效 = ERROR 拦下），行数超预算 = WARN 只提示。
// 接入点：pre-push 门禁（改动涉及入口文件时）与 CI（每次推送全量跑）。
// 动机：指向已删文件的入口条目比没有条目更糟——人与代理按图索骥必落空。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';
import { ROOT, backtickPaths, trackedFiles } from '../lib.mjs';

// 通用约定：仓库内任意层级的 AGENTS.md 都是入口文件；行数预算取通用默认值。
const DEFAULT_BUDGET = 200;
const ENTRIES = trackedFiles()
  .filter((f) => f === 'AGENTS.md' || f.endsWith('/AGENTS.md'))
  .map((rel) => ({ rel, budget: DEFAULT_BUDGET }));

const errors = [];
const warns = [];

for (const entry of ENTRIES) {
  const file = join(ROOT, ...entry.rel.split('/'));
  if (!existsSync(file)) {
    errors.push(`入口文件缺失：${entry.rel}`);
    continue;
  }
  const text = readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/).length;
  if (lines > entry.budget) {
    warns.push(`${entry.rel}：${lines} 行，超预算 ${entry.budget}（把细节下沉到正典文档，此处留指针）`);
  }
  const base = dirname(file);
  for (const p of backtickPaths(text)) {
    // 双基准解析：先按入口文件所在目录，再回退仓库根（嵌套入口常引用根级目录，如前端入口提到 `docker/`）
    const local = join(base, ...p.split('/'));
    const fromRoot = join(ROOT, ...p.split('/'));
    if (!existsSync(local) && !existsSync(fromRoot)) {
      errors.push(`${entry.rel} 引用了不存在的路径：${p}`);
    }
  }
}

for (const w of warns) console.log(`WARN  ${w}`);
if (errors.length) {
  for (const e of errors) console.log(`ERROR ${e}`);
  console.log('');
  console.log(`AGENTS 入口体检：${errors.length} 处指针失效；修复＝更正入口路径或恢复目标文件`);
  process.exit(1);
}
console.log(`AGENTS 入口体检：${ENTRIES.length} 个入口文件指针全部有效` + (warns.length ? `（${warns.length} 条行数预算提醒）` : ''));
