#!/usr/bin/env node
/**
 * 模块依赖一致性校验：比对 `.agent/standards/java/01-分层与包结构.md` §1 的表与各模块 pom.xml。
 *
 * <p>pom 里有、表里没有的边 = ERROR（新增依赖未登记，推送拦下）；
 * 表里有、pom 里没有的边 = WARN（文档过期，提示清理）；
 * 「被谁依赖」列与 pom 反推出的结果不一致 = WARN。
 *
 * <p>不判断依赖方向是否合理（由 arch-scan 负责），只管文档与现实是否一致。
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DOC = join(ROOT, '.agent', 'standards', 'java', '01-分层与包结构.md');

/** 从文档 §1 的表里读出 { 模块名: { deps, dependents } } */
function readMatrix() {
  const text = readFileSync(DOC, 'utf8');
  const rows = text.split(/\r?\n/).filter((line) => /^\|\s*`workbuddy-[a-z]+`\s*\|/.test(line));
  if (rows.length === 0) {
    throw new Error('文档 §1 未解析到模块表：请保持表头列顺序为 模块 | 职责 | 对外提供 | 依赖 | 被谁依赖');
  }
  const matrix = new Map();
  for (const row of rows) {
    const cells = row.split('|').map((c) => c.trim());
    const name = cells[1].replace(/`/g, '');
    const deps = splitList(cells[4]);
    const dependents = splitList(cells[5]);
    matrix.set(name, { deps: new Set(deps), dependents: new Set(dependents) });
  }
  return matrix;
}

/** 「common、infra」或「无」→ ['workbuddy-common', 'workbuddy-infra'] */
function splitList(cell) {
  const raw = (cell || '').replace(/`/g, '').trim();
  if (!raw || raw === '无' || raw === '—') {
    return [];
  }
  return raw
    .split(/[、,，]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => (s.startsWith('workbuddy-') ? s : `workbuddy-${s}`));
}

/** 从某模块 pom 读出它依赖的 workbuddy-* 模块 */
function readPomDeps(module) {
  const pom = join(ROOT, module, 'pom.xml');
  if (!existsSync(pom)) {
    return null;
  }
  const text = readFileSync(pom, 'utf8');
  const deps = new Set();
  for (const block of text.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const m = block[1].match(/<artifactId>(workbuddy-[a-z]+)<\/artifactId>/);
    if (m && m[1] !== module) {
      deps.add(m[1]);
    }
  }
  return deps;
}

const errors = [];
const warns = [];

let matrix;
try {
  matrix = readMatrix();
} catch (e) {
  console.log(`ERROR ${e.message}`);
  process.exit(1);
}

const modules = readdirSync(ROOT, { withFileTypes: true })
  .filter((d) => d.isDirectory() && d.name.startsWith('workbuddy-'))
  .map((d) => d.name)
  .sort();

// 文档里有、仓库里没有的模块
for (const name of matrix.keys()) {
  if (!modules.includes(name)) {
    warns.push(`${name}：文档登记了该模块，但仓库中不存在`);
  }
}

// 第一遍：读 pom，建立实际依赖与反向关系
const actualDeps = new Map();
const actualDependents = new Map(modules.map((m) => [m, new Set()]));
for (const module of modules) {
  const deps = readPomDeps(module);
  if (deps === null) {
    warns.push(`${module}：没有 pom.xml，跳过`);
    actualDeps.set(module, null);
    continue;
  }
  actualDeps.set(module, deps);
  for (const dep of deps) {
    if (actualDependents.has(dep)) {
      actualDependents.get(dep).add(module);
    }
  }
}

// 第二遍：与文档比对
for (const module of modules) {
  const actual = actualDeps.get(module);
  if (!actual) {
    continue;
  }
  const doc = matrix.get(module);
  if (!doc) {
    if (actual.size > 0) {
      errors.push(`${module}：pom 里有 ${actual.size} 条 workbuddy 依赖，但文档 §1 没有该模块`);
    }
    continue;
  }
  for (const dep of actual) {
    if (!doc.deps.has(dep)) {
      errors.push(`${module} → ${dep}：pom 中已存在，文档 §1 未登记（新增依赖须登记并写 ADR）`);
    }
  }
  for (const dep of doc.deps) {
    if (!actual.has(dep)) {
      warns.push(`${module} → ${dep}：文档已登记，pom 中不存在（文档过期）`);
    }
  }
  const actualDependentsOfModule = actualDependents.get(module) || new Set();
  for (const dependent of actualDependentsOfModule) {
    if (!doc.dependents.has(dependent)) {
      warns.push(`${module} 的被依赖列缺少 ${dependent}`);
    }
  }
  for (const dependent of doc.dependents) {
    if (!actualDependentsOfModule.has(dependent)) {
      warns.push(`${module} 的被依赖列多出 ${dependent}（实际未依赖）`);
    }
  }
}

for (const w of warns) {
  console.log(`WARN  ${w}`);
}
if (errors.length) {
  for (const e of errors) {
    console.log(`ERROR ${e}`);
  }
  console.log('');
  console.log(`模块依赖一致性：${errors.length} 处未登记依赖；修复＝改 .agent/standards/java/01-分层与包结构.md §1，或删掉 pom 里多余的依赖`);
  process.exit(1);
}
const edgeCount = [...matrix.values()].reduce((sum, v) => sum + v.deps.size, 0);
console.log(
  `模块依赖一致性：${modules.length} 个模块、${edgeCount} 条已登记边，与 pom.xml 一致` +
    (warns.length ? `（${warns.length} 条提示）` : '')
);
