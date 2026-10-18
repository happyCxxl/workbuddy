#!/usr/bin/env node
// 模块依赖一致性：把「模块依赖白名单」与各模块 pom.xml 双向核对。
// 白名单在 .agent/tools/modules.json；基础模块（契约层、基础层）视为通配允许，不要求在每行登记。
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const CONFIG = join(ROOT, '.agent', 'tools', 'modules.json');

const errors = [];
const warns = [];

let config;
try {
  config = JSON.parse(readFileSync(CONFIG, 'utf8'));
} catch (e) {
  console.log(`ERROR 读取模块白名单失败：${CONFIG}（${e.message}）`);
  process.exit(1);
}

const BASE = new Set(config.baseModules ?? []);
const MATRIX = new Map(Object.entries(config.modules ?? {}));

const modules = readdirSync(ROOT, { withFileTypes: true })
  .filter((d) => d.isDirectory() && d.name.startsWith('workbuddy-'))
  .map((d) => d.name)
  .sort();

/** 从某模块 pom 读出它依赖的 workbuddy-* 模块 */
function readPomDeps(module) {
  const pom = join(ROOT, module, 'pom.xml');
  if (!existsSync(pom)) return null;
  const text = readFileSync(pom, 'utf8');
  const deps = new Set();
  for (const block of text.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    const m = block[1].match(/<artifactId>(workbuddy-[a-z]+)<\/artifactId>/);
    if (m && m[1] !== module) deps.add(m[1]);
  }
  return deps;
}

// 文档登记了、仓库里没有
for (const name of MATRIX.keys()) {
  if (!modules.includes(name)) warns.push(`${name}：白名单登记了该模块，但仓库中不存在`);
}

for (const module of modules) {
  const actual = readPomDeps(module);
  if (actual === null) {
    warns.push(`${module}：没有 pom.xml，跳过`);
    continue;
  }
  const allowed = new Set(MATRIX.get(module) ?? []);
  if (!MATRIX.has(module)) {
    errors.push(`${module}：仓库中存在，但白名单未登记该模块`);
  }
  for (const dep of actual) {
    if (BASE.has(dep)) continue; // 基础层通配允许
    if (!allowed.has(dep)) {
      errors.push(`${module} → ${dep}：pom 中已存在，白名单未登记（新增依赖须登记并写明依据）`);
    }
  }
  for (const dep of allowed) {
    if (!actual.has(dep)) warns.push(`${module} → ${dep}：白名单已登记，pom 中不存在（白名单过期）`);
  }
}

for (const w of warns) console.log(`WARN  ${w}`);
if (errors.length) {
  for (const e of errors) console.log(`ERROR ${e}`);
  console.log('');
  console.log(`模块依赖一致性：${errors.length} 处未登记依赖；修复＝改 .agent/tools/modules.json，或删掉 pom 里多余的依赖`);
  process.exit(1);
}

const edgeCount = [...MATRIX.values()].reduce((sum, list) => sum + list.length, 0);
console.log(
  `模块依赖一致性：${modules.length} 个模块、${edgeCount} 条已登记边，与 pom.xml 一致` +
    (warns.length ? `（${warns.length} 条提示）` : '')
);
