#!/usr/bin/env node
// 模块结构检查：解析仓库内的 pom.xml，校验模块声明存在、模块间依赖无环、无自依赖。
// 通用约定：任何 Maven 多模块仓库都适用；不读取项目专有的白名单文件。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, posix } from 'node:path';
import { ROOT, trackedFiles } from '../lib.mjs';

const errors = [];
const warns = [];

/** 去掉与模块自身标识无关的块，剩下的第一个 artifactId 即本模块 */
function stripBlocks(xml) {
  return xml
    .replace(/<parent>[\s\S]*?<\/parent>/g, '')
    .replace(/<dependencies>[\s\S]*?<\/dependencies>/g, '')
    .replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, '')
    .replace(/<build>[\s\S]*?<\/build>/g, '')
    .replace(/<profiles>[\s\S]*?<\/profiles>/g, '')
    .replace(/<reporting>[\s\S]*?<\/reporting>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '');
}

function parsePom(rel) {
  const xml = readFileSync(join(ROOT, rel), 'utf8');
  const body = stripBlocks(xml);
  const artifactId = /<artifactId>([\w.-]+)<\/artifactId>/.exec(body)?.[1] ?? null;
  const modules = [...xml.matchAll(/<module>([^<]+)<\/module>/g)].map((m) => m[1].trim());
  const deps = [...xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)]
    .map((m) => /<artifactId>([\w.-]+)<\/artifactId>/.exec(m[1])?.[1])
    .filter(Boolean);
  return { rel, dir: dirname(rel) === '.' ? '' : dirname(rel), artifactId, modules, deps };
}

const poms = trackedFiles().filter((f) => f.endsWith('pom.xml')).map(parsePom);
const byArtifact = new Map();
for (const pom of poms) {
  if (pom.artifactId) {
    if (byArtifact.has(pom.artifactId)) {
      warns.push(`artifactId 重复：${pom.artifactId}（${byArtifact.get(pom.artifactId).rel} 与 ${pom.rel}）`);
    } else {
      byArtifact.set(pom.artifactId, pom);
    }
  }
}

if (poms.length === 0) {
  console.log('模块结构检查：未发现 pom.xml，跳过');
  process.exit(0);
}

// 模块声明指向的目录必须存在
for (const pom of poms) {
  for (const m of pom.modules) {
    const target = pom.dir ? posix.join(pom.dir, m) : m;
    if (!existsSync(join(ROOT, target))) {
      errors.push(`${pom.rel} 声明的模块目录不存在：${target}`);
    }
  }
}

// 模块间依赖：自依赖、环
const graph = new Map();
for (const pom of poms) {
  if (!pom.artifactId) continue;
  const deps = new Set();
  for (const d of pom.deps) {
    if (d === pom.artifactId) {
      errors.push(`${pom.rel} 依赖了自身：${d}`);
      continue;
    }
    if (byArtifact.has(d)) deps.add(d);
  }
  graph.set(pom.artifactId, deps);
}

const state = new Map();
function visit(node, stack) {
  if (state.get(node) === 'done') return;
  if (state.get(node) === 'visiting') {
    const cycle = [...stack.slice(stack.indexOf(node)), node].join(' -> ');
    errors.push(`模块依赖成环：${cycle}`);
    return;
  }
  state.set(node, 'visiting');
  for (const dep of graph.get(node) ?? []) visit(dep, [...stack, node]);
  state.set(node, 'done');
}
for (const node of graph.keys()) visit(node, []);

for (const w of warns) console.log(`WARN  ${w}`);
if (errors.length) {
  for (const e of errors) console.log(`ERROR ${e}`);
  console.log('');
  console.log(`模块结构检查：${errors.length} 处问题；修复＝改 pom.xml 的模块声明或依赖`);
  process.exit(1);
}
const edgeCount = [...graph.values()].reduce((sum, s) => sum + s.size, 0);
console.log(
  `模块结构检查：${graph.size} 个模块、${edgeCount} 条模块间依赖，无环、无自依赖` +
    (warns.length ? `（${warns.length} 条提示）` : '')
);
