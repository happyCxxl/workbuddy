#!/usr/bin/env node
// 规范边界检查：`.agent/` 必须自包含——规范正文只能引用 `.agent/` 内的路径。
// 只判定「带斜杠的路径引用」这一可机器判定的部分：反引号里出现 `a/b.md` 这类串时，
// 按「文档所在目录」与「.agent 根」两个基准解析，必须存在且落在 .agent 内。
// 正文里点名外部文件名（如构建文件名、入口文件名）无法客观判定，靠评审，不在本检查器范围内。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, resolve, sep } from 'node:path';
import process from 'node:process';
import { ROOT, backtickPaths, trackedFiles } from '../lib.mjs';

const AGENT_ROOT = join(ROOT, '.agent');
const inside = (p) => p === AGENT_ROOT || p.startsWith(AGENT_ROOT + sep);

const errors = [];
const files = trackedFiles().filter((f) => f.startsWith('.agent/') && f.endsWith('.md'));

for (const rel of files) {
  const file = join(ROOT, ...rel.split('/'));
  if (!existsSync(file)) continue; // 已删除但仍在索引里的文件
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    for (const p of backtickPaths(line, {
      requireSlash: true,
      requireExtension: true,
      skipElision: true,
      stripLinks: true,
    })) {
      if (/^https?:/.test(p)) continue;
      // 三个基准：文档所在目录、.agent 根（包内相对写法）、仓库根（`.agent/...` 全路径写法）
      const bases = [resolve(dirname(file), p), resolve(AGENT_ROOT, p), resolve(ROOT, p)];
      const insideHit = bases.find((c) => inside(c) && existsSync(c));
      if (insideHit) continue;
      const outsideHit = bases.find((c) => !inside(c) && existsSync(c));
      errors.push(
        `${rel}:${i + 1} ${outsideHit ? `引用了 .agent 之外的路径：${p}` : `路径不存在：${p}`}`,
      );
    }
  });
}

if (errors.length === 0) {
  console.log(`规范边界：通过（${files.length} 个规范文件只引用 .agent 内路径）`);
  process.exit(0);
}
for (const e of errors) console.log(`ERROR ${e}`);
console.log('');
console.log(`规范边界：ERROR ${errors.length}；规范要能被别的项目整包复用，就不能指向包外文件`);
console.log('复跑：node .agent/tools/common/check-scope.mjs');
process.exit(1);
