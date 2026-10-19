#!/usr/bin/env node
/**
 * 中间产物门禁：拦住被 `git add -A` 一并带入版本库的构建、检查、命令输出。
 *
 * 触发场景：跑完测试或检查顺手 `git add -A`，把 `*-out.txt`、覆盖率数据、构建目录
 * 一起提交。后果是 diff 里混入与本次改动无关的整文件替换，评审要人工分辨；
 * 这些文件还会随每条命令重写，反复制造噪声。
 *
 * 扫描范围：仓库内**已跟踪**的文件（未跟踪的由 .gitignore 负责）。
 * 无豁免：命中就删掉并补 .gitignore，不给单条路径开口子。
 *
 * 退出码：有命中 → 1；全干净 → 0。
 */
import process from 'node:process';
import { trackedFiles } from '../lib.mjs';

/** 目录形态：构建产物与依赖目录，出现即判定 */
const DIR_PATTERNS = [
  /(^|\/)(target|build|dist|out|bin|obj|node_modules|coverage|\.gradle|\.next)\//,
];

/** 文件形态：扩展名与文件名模式 */
const FILE_PATTERNS = [
  { pattern: /\.(class|jar|war|ear|exec|lcov|tmp|bak|orig|rej|swp)$/i, kind: '编译产物或临时文件' },
  { pattern: /-out\.(txt|log)$/i, kind: '命令输出' },
  { pattern: /(^|\/)jacoco\.(csv|xml|exec)$/i, kind: '覆盖率报告' },
  { pattern: /(^|\/)(\.DS_Store|Thumbs\.db|desktop\.ini)$/i, kind: '操作系统残留' },
  { pattern: /~$/, kind: '编辑器备份' },
];

const files = trackedFiles();
const hits = [];

for (const file of files) {
  const rel = file.split('\\').join('/');
  const dirHit = DIR_PATTERNS.find((p) => p.test(rel));
  if (dirHit) {
    hits.push({ file: rel, kind: '构建产物目录' });
    continue;
  }
  const fileHit = FILE_PATTERNS.find((p) => p.pattern.test(rel));
  if (fileHit) {
    hits.push({ file: rel, kind: fileHit.kind });
  }
}

if (hits.length === 0) {
  console.log(`扫描已跟踪文件 ${files.length} 个；未发现中间产物 ✓`);
  process.exit(0);
}

console.log(`中间产物门禁命中 ${hits.length} 处（不得提交进版本库）：`);
for (const hit of hits) {
  console.log(`  ${hit.file}: ${hit.kind}`);
}
console.log('  修法：git rm --cached <文件>，并把形态补进 .gitignore；命令输出改写到仓库外或已忽略目录。');
process.exit(1);
