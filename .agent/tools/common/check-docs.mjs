#!/usr/bin/env node
// 文档结构检查：Markdown 中不得出现「变更记录」「决策记录」小节。
// 通用约定：扫描仓库内受版本控制的 .md 文件；不依赖词表，不设豁免。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { ROOT, trackedFiles } from '../lib.mjs';

const FORBIDDEN = /^#{1,6}\s*(变更记录|决策记录)\s*$/;

const errors = [];
const files = trackedFiles().filter((f) => f.endsWith('.md'));

for (const file of files) {
  let lines;
  try {
    lines = readFileSync(join(ROOT, file), 'utf8').split(/\r?\n/);
  } catch {
    continue;
  }
  lines.forEach((line, i) => {
    if (FORBIDDEN.test(line.trim())) {
      errors.push(`${file}:${i + 1} [JAVA-DOC-009] 不得出现「变更记录」「决策记录」小节`);
    }
  });
}

if (errors.length === 0) {
  console.log(`文档结构检查：通过（${files.length} 个文件）`);
  process.exit(0);
}
for (const e of errors) console.log(`ERROR ${e}`);
console.log('');
console.log(`文档结构检查：ERROR ${errors.length}`);
process.exit(1);
