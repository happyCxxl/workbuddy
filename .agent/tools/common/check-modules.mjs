#!/usr/bin/env node
// 模块结构检查：解析仓库内的 pom.xml，校验模块声明的目录、父子声明、依赖存在性、成环与自依赖。
// 通用约定：任何 Maven 多模块仓库都适用；不读取项目专有的白名单文件。
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join, posix } from 'node:path';
import process from 'node:process';
import { ROOT, trackedFiles } from '../lib.mjs';

const errors = [];
const push = (msg) => errors.push(msg);

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
  const groupId = /<groupId>([\w.-]+)<\/groupId>/.exec(body)?.[1] ?? null;
  const parent = /<parent>[\s\S]*?<\/parent>/.exec(xml)?.[0] ?? '';
  const parentArtifactId = /<artifactId>([\w.-]+)<\/artifactId>/.exec(parent)?.[1] ?? null;
  const modules = [...xml.matchAll(/<module>([^<]+)<\/module>/g)].map((m) => m[1].trim());
  const deps = [...xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map((m) => ({
    groupId: /<groupId>([\w.-]+)<\/groupId>/.exec(m[1])?.[1] ?? null,
    artifactId: /<artifactId>([\w.-]+)<\/artifactId>/.exec(m[1])?.[1] ?? null,
  }));
  return {
    rel,
    dir: dirname(rel) === '.' ? '' : dirname(rel),
    artifactId,
    groupId,
    parentArtifactId,
    modules,
    deps: deps.filter((d) => d.artifactId),
  };
}

const poms = trackedFiles().filter((f) => f.endsWith('pom.xml')).map(parsePom);

if (poms.length === 0) {
  console.log('模块结构检查：未发现 pom.xml，跳过');
  process.exit(0);
}

const byArtifact = new Map();
for (const pom of poms) {
  if (!pom.artifactId) continue;
  if (byArtifact.has(pom.artifactId)) {
    push(`artifactId 重复：${pom.artifactId}（${byArtifact.get(pom.artifactId).rel} 与 ${pom.rel}）`);
    continue;
  }
  byArtifact.set(pom.artifactId, pom);
}

/** 仓库自身的 groupId：根 pom 的 groupId，缺省取任一模块的 groupId */
const rootGroupId =
  poms.find((p) => p.dir === '' && p.groupId)?.groupId ??
  poms.find((p) => p.groupId)?.groupId ??
  null;

// 1. 声明的模块目录必须存在且含 pom.xml
for (const pom of poms) {
  for (const m of pom.modules) {
    const target = pom.dir ? posix.join(pom.dir, m) : m;
    if (!existsSync(join(ROOT, target))) {
      push(`${pom.rel} 声明的模块目录不存在：${target}`);
    } else if (!existsSync(join(ROOT, target, 'pom.xml'))) {
      push(`${pom.rel} 声明的模块缺少 pom.xml：${target}`);
    }
  }
}

// 2. 有父模块的 pom，必须被父模块声明
for (const pom of poms) {
  if (!pom.parentArtifactId || !byArtifact.has(pom.parentArtifactId)) continue;
  const parent = byArtifact.get(pom.parentArtifactId);
  const declared = parent.modules.map((m) => (parent.dir ? posix.join(parent.dir, m) : m));
  if (!declared.includes(pom.dir)) {
    push(`${pom.rel} 未被父模块 ${parent.rel} 声明`);
  }
}

// 3. 同 groupId 的模块依赖必须存在；自依赖直接报错
const graph = new Map();
for (const pom of poms) {
  if (!pom.artifactId) continue;
  const deps = new Set();
  for (const d of pom.deps) {
    const dep = d.artifactId;
    if (dep === pom.artifactId) {
      push(`${pom.rel} 依赖了自身：${dep}`);
      continue;
    }
    if (byArtifact.has(dep)) {
      deps.add(dep);
      continue;
    }
    if (rootGroupId && d.groupId === rootGroupId) {
      push(`${pom.rel} 依赖的模块不存在：${d.groupId}:${dep}`);
    }
  }
  graph.set(pom.artifactId, deps);
}

// 4. 模块间依赖不得成环
const state = new Map();
function visit(node, stack) {
  if (state.get(node) === 'done') return;
  if (state.get(node) === 'visiting') {
    const cycle = [...stack.slice(stack.indexOf(node)), node].join(' -> ');
    push(`模块依赖成环：${cycle}`);
    return;
  }
  state.set(node, 'visiting');
  for (const dep of graph.get(node) ?? []) visit(dep, [...stack, node]);
  state.set(node, 'done');
}
for (const node of graph.keys()) visit(node, []);

if (errors.length) {
  for (const e of errors) console.log(`ERROR ${e}`);
  console.log('');
  console.log(`模块结构检查：${errors.length} 处问题；修复＝改 pom.xml 的模块声明或依赖`);
  process.exit(1);
}
const edgeCount = [...graph.values()].reduce((sum, s) => sum + s.size, 0);
console.log(`模块结构检查：${graph.size} 个模块、${edgeCount} 条模块间依赖，结构完整`);
