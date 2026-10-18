#!/usr/bin/env node
// AGENTS 入口体检：入口文件反引号里引用的仓库路径必须真实存在（指针失效 = ERROR 拦下），行数超预算 = WARN 只提示。
// 接入点：pre-push 门禁（改动涉及入口文件时）与 CI（每次推送全量跑）。
// 动机：指向已删文件的入口条目比没有条目更糟——人与代理按图索骥必落空。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { trackedFiles } from './lib.mjs';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

// 通用约定：仓库内任意层级的 AGENTS.md 都是入口文件；行数预算取通用默认值。
const DEFAULT_BUDGET = 200;
const ENTRIES = trackedFiles()
  .filter((f) => f === 'AGENTS.md' || f.endsWith('/AGENTS.md'))
  .map((rel) => ({ rel, budget: DEFAULT_BUDGET }));

// 只认「像仓库路径」的反引号串：剥掉命令前缀后不含空白/特殊字符，且含 / 或以已知扩展名结尾
const CMD_PREFIX = /^(sh|node|cd|mvn|pnpm|npx|java|git|docker)\s+/;
const PATH_EXT = /\.(md|mjs|js|cjs|ts|vue|xml|yml|yaml|json|sh|ps1|bat|java|properties|sql|html|css)$/;

function pathCandidates(text) {
  const out = new Set();
  for (const m of text.matchAll(/`([^`\n]+)`/g)) {
    const s = m[1].trim().replace(CMD_PREFIX, '');
    if (!s || /[\s(){}<>*=$"'|&;,:^\\]/.test(s) || s.startsWith('-')) continue;
    if (s.includes('/') || PATH_EXT.test(s)) out.add(s);
  }
  return [...out];
}

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
  for (const p of pathCandidates(text)) {
    // 双基准解析：先按入口文件所在目录，再回退仓库根（嵌套入口常引用根级目录，如 web/AGENTS.md 提到 `docker/`）
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
