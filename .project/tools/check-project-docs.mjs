#!/usr/bin/env node
/**
 * 档案漂移检查（验证层）：按 `.project/config.json` 的声明，把档案里可机检的事实与仓库实际对比。
 *
 * 引擎与项目技术栈无关：路径、后缀、注解、前缀、忽略项全部来自配置；文件内不出现任何项目词汇。
 * 检查类型（由配置的 checks[].type 选择）：
 *   git-timeline  档案 01 的时间线与仓库地址
 *   modules       模块清单（构建文件的 module 声明）
 *   edges         模块间依赖边
 *   dirs          顶层目录
 *   files         由文件集合推导的名单（glob 匹配 + 去后缀）
 *   annotations   由源码注解捕获的名单（glob 匹配 + 正则）
 *   services      编排文件里的服务是否在档案中提及
 *   count         文件数量
 * 未识别的类型只提示，不报错。
 *
 * 退出码：有 ERROR 时为 1。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(TOOLS_DIR, '..', '..');
const ARCHIVE_DIR = join(ROOT, '.project', 'archive');
const CONFIG = join(ARCHIVE_DIR, 'config.json');
const STALE_DAYS = 90;
const DOC_PREFIXES = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11'];

const errors = [];
const warns = [];
const err = (where, msg) => errors.push(`${where}：${msg}`);
const warn = (where, msg) => warns.push(`${where}：${msg}`);

const read = (p) => readFileSync(p, 'utf8');
const git = (args) => {
  try {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
};

function docPath(prefix) {
  if (!existsSync(ARCHIVE_DIR)) return null;
  const name = readdirSync(ARCHIVE_DIR).find((f) => f.startsWith(prefix) && f.endsWith('.md'));
  return name ? join(ARCHIVE_DIR, name) : null;
}

/* ---------------- markdown 解析 ---------------- */

function tableRows(text) {
  return text
    .split(/\r?\n/)
    .filter((l) => l.trim().startsWith('|'))
    .map((l) => l.split('|').slice(1, -1).map((c) => c.trim()));
}

function section(text, heading) {
  const lines = text.split(/\r?\n/);
  const start = lines.findIndex((l) => l.startsWith('## ') && l.includes(heading));
  if (start < 0) return '';
  let end = lines.findIndex((l, i) => i > start && l.startsWith('## '));
  if (end < 0) end = lines.length;
  return lines.slice(start + 1, end).join('\n');
}

function backticks(s) {
  return [...s.matchAll(/`([^`]+)`/g)].map((m) => m[1].trim());
}

function fences(text) {
  const out = [];
  let cur = null;
  for (const line of text.split(/\r?\n/)) {
    if (line.trim().startsWith('```')) {
      if (cur) {
        out.push(cur.join('\n'));
        cur = null;
      } else {
        cur = [];
      }
    } else if (cur) {
      cur.push(line);
    }
  }
  return out;
}

/* ---------------- glob 与文件遍历 ---------------- */

function globToRegex(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i += 1) {
    const c = glob[i];
    if (c === '*') {
      if (glob[i + 1] === '*') {
        re += '.*';
        i += 1;
      } else {
        re += '[^/]*';
      }
    } else if (c === '?') {
      re += '.';
    } else if ('.+^$()|[]{}'.includes(c)) {
      re += '\\' + c;
    } else {
      re += c;
    }
  }
  return new RegExp('^' + re + '$');
}

function walkFiles(relDir, filter, out = []) {
  const dir = join(ROOT, relDir);
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (!['node_modules', 'target', 'dist', 'build', 'out', 'coverage', '.git'].includes(e.name)) {
        walkFiles(p.slice(ROOT.length + 1).replace(/\\/g, '/'), filter, out);
      }
    } else if (e.isFile() && filter(e.name)) {
      out.push(p);
    }
  }
  return out;
}

function matchGlob(glob) {
  const re = globToRegex(glob);
  const all = walkFiles('', () => true);
  return all
    .map((f) => f.slice(ROOT.length + 1).replace(/\\/g, '/'))
    .filter((rel) => re.test(rel));
}

/* ---------------- 事实提取器 ---------------- */

function pomModules(file) {
  const p = join(ROOT, file);
  if (!existsSync(p)) return null;
  return [...read(p).matchAll(/<module>([^<]+)<\/module>/g)].map((m) => m[1].trim());
}

function pomEdges(rootPom, modulePrefix) {
  const mods = pomModules(rootPom);
  if (!mods) return null;
  // 模块目录相对构建文件所在目录解析：构建文件不必在仓库根（多工程仓库常见一级子目录）。
  const base = dirname(rootPom);
  const edges = new Map();
  for (const m of mods) {
    const p = join(ROOT, base, m, 'pom.xml');
    if (!existsSync(p)) continue;
    // 父工程坐标在 <parent> 块里，不是模块依赖：先剥掉，再取依赖块里的模块名
    const xml = read(p).replace(/<parent>[\s\S]*?<\/parent>/, '');
    const deps = [...xml.matchAll(new RegExp('<artifactId>(' + modulePrefix + '[a-z-]+)</artifactId>', 'g'))]
      .map((x) => x[1])
      .filter((d) => d !== m);
    edges.set(m.replace(modulePrefix, ''), [...new Set(deps.map((d) => d.replace(modulePrefix, '')))].sort());
  }
  return edges;
}

function fileBasenames(glob, suffix) {
  return matchGlob(glob).map((p) => {
    const base = p.split('/').pop().replace(/\.[^.]+$/, '');
    return suffix && base.endsWith(suffix) ? base.slice(0, -suffix.length) : base;
  });
}

function annotationCaptures(glob, pattern) {
  const re = new RegExp(pattern, 'g');
  const out = [];
  for (const rel of matchGlob(glob)) {
    const text = read(join(ROOT, rel));
    for (const m of text.matchAll(re)) out.push(m[1]);
  }
  return out;
}

function composeServices(file) {
  const p = join(ROOT, file);
  if (!existsSync(p)) return null;
  const out = [];
  let inServices = false;
  for (const line of read(p).split(/\r?\n/)) {
    if (/^services:\s*$/.test(line)) {
      inServices = true;
      continue;
    }
    if (inServices && /^\S/.test(line)) break;
    const m = inServices && line.match(/^ {2}([A-Za-z0-9_-]+):\s*$/);
    if (m) out.push(m[1]);
  }
  return out;
}

function countFiles(glob) {
  return matchGlob(glob).length;
}

function topDirs(ignore) {
  const skip = new Set(ignore ?? []);
  return readdirSync(ROOT, { withFileTypes: true })
    .filter((d) => d.isDirectory() && !skip.has(d.name))
    .map((d) => d.name);
}

/* ---------------- 比对 ---------------- */

function setDiff(actual, claimed, name) {
  for (const v of actual) if (!claimed.has(v)) err(name, `仓库有 ${v}，档案未列出`);
  for (const v of claimed) if (!actual.has(v)) err(name, `档案列出 ${v}，仓库无此项`);
}

function checkGitTimeline(doc, spec, name) {
  const rows = tableRows(read(doc));
  const value = (label) => rows.find((r) => r[0] === label)?.[1] ?? '';
  const created = value('创建时间').match(/\d{4}-\d{2}-\d{2}/)?.[0];
  const first = git(['log', '--reverse', '--format=%ad', '--date=short']).split(/\r?\n/)[0];
  if (created && first && created !== first) {
    err(name, `创建时间写 ${created}，仓库首次提交为 ${first}`);
  }
  const last = value('最近提交').match(/\b[0-9a-f]{7,40}\b/)?.[0];
  const latest = git(['log', '-1', '--format=%h']);
  if (last && latest && !latest.startsWith(last) && !last.startsWith(latest)) {
    err(name, `最近提交写 ${last}，仓库为 ${latest}`);
  }
  const remote = value('远端仓库').replace(/^<|>$/g, '');
  const actualRemote = git(['config', '--get', 'remote.origin.url']);
  if (remote && actualRemote && remote !== actualRemote) {
    err(name, `远端仓库写 ${remote}，git 为 ${actualRemote}`);
  }
}

function checkModules(doc, spec, name) {
  const actual = pomModules(spec.pom);
  if (!actual) {
    warn(name, `找不到构建文件 ${spec.pom}，跳过模块比对`);
    return;
  }
  const strip = spec.stripPrefix ?? '';
  const actualSet = new Set(actual.map((m) => m.replace(strip, '')));
  const claimed = new Set();
  for (const r of tableRows(section(read(doc), spec.target))) {
    for (const t of backticks(r[0])) if (!t.includes('/')) claimed.add(t);
  }
  setDiff(actualSet, claimed, `${name} 模块清单`);
}

function checkEdges(doc, spec, name) {
  const actual = pomEdges(spec.pom, spec.modulePrefix ?? '');
  if (!actual) {
    warn(name, `找不到构建文件 ${spec.pom}，跳过依赖边比对`);
    return;
  }
  const claimed = new Map();
  for (const block of fences(section(read(doc), spec.target))) {
    for (const line of block.split(/\r?\n/)) {
      const m = line.match(/^\s*([a-z][a-z0-9-]*)\s*→\s*(.+)$/);
      if (m) claimed.set(m[1], m[2].split(/[,，]/).map((s) => s.trim()).filter(Boolean).sort());
    }
  }
  for (const [mod, deps] of actual) {
    const doc = claimed.get(mod) ?? [];
    const same = deps.length === doc.length && deps.every((d, i) => d === doc[i]);
    if (!same) err(`${name} 依赖边`, `${mod} 实际 [${deps.join(', ')}]，档案写 [${doc.join(', ')}]`);
  }
}

function checkDirs(doc, spec, name) {
  const actual = new Set(topDirs(spec.ignore));
  const claimed = new Set();
  for (const r of tableRows(section(read(doc), spec.target))) {
    for (const t of backticks(r[0])) if (/^[\w.-]+$/.test(t)) claimed.add(t);
  }
  for (const d of claimed) if (!actual.has(d)) err(`${name} 目录地图`, `档案写了 ${d}，仓库无此顶层目录`);
  for (const d of actual) if (!claimed.has(d)) warn(`${name} 目录地图`, `仓库有顶层目录 ${d}，档案未列出`);
}

function checkFiles(doc, spec, name) {
  const ignore = new Set(spec.ignore ?? []);
  const actual = new Set(fileBasenames(spec.glob, spec.suffix ?? '').filter((n) => !ignore.has(n)));
  const claimed = new Set();
  for (const t of backticks(section(read(doc), spec.target))) {
    if (/^[A-Za-z][A-Za-z0-9_]*$/.test(t) && !ignore.has(t)) claimed.add(t);
  }
  setDiff(actual, claimed, `${name} 名单`);
}

function checkAnnotations(doc, spec, name) {
  const actual = new Set(annotationCaptures(spec.glob, spec.pattern));
  const claimed = new Set();
  for (const r of tableRows(section(read(doc), spec.target))) {
    for (const t of backticks(r[0])) {
      if (!t.startsWith('/')) continue;
      claimed.add(spec.stripSuffix && t.endsWith(spec.stripSuffix) ? t.slice(0, -spec.stripSuffix.length) : t);
    }
  }
  setDiff(actual, claimed, `${name} 清单`);
}

function checkServices(doc, spec, name) {
  const services = composeServices(spec.file);
  if (!services) {
    warn(name, `找不到编排文件 ${spec.file}，跳过服务比对`);
    return;
  }
  const text = read(doc).toLowerCase();
  for (const s of services) {
    if (spec.skipPrefix && s.startsWith(spec.skipPrefix)) continue;
    if (!text.includes(s.toLowerCase())) warn(`${name} 服务`, `编排服务 ${s} 未在档案提及`);
  }
}

function checkCount(doc, spec, name) {
  const m = read(doc).match(new RegExp(spec.pattern));
  if (!m) {
    warn(name, '档案中没有可计数目标，跳过数量比对');
    return;
  }
  const actual = countFiles(spec.glob);
  if (Number(m[1]) !== actual) err(`${name} 数量`, `档案写 ${m[1]}，实际 ${actual}`);
}

const CHECKERS = {
  'git-timeline': checkGitTimeline,
  modules: checkModules,
  edges: checkEdges,
  dirs: checkDirs,
  files: checkFiles,
  annotations: checkAnnotations,
  services: checkServices,
  count: checkCount,
};

/* ---------------- 主流程 ---------------- */

if (!existsSync(ARCHIVE_DIR)) {
  console.log('档案漂移检查：尚未接入（无 archive 目录），跳过');
  process.exit(0);
}

const config = existsSync(CONFIG) ? JSON.parse(read(CONFIG)) : { docs: {} };
const docsCfg = config.docs ?? {};
const prefixesArg = process.argv.find((a) => a.startsWith('--prefixes='));
const only = prefixesArg ? new Set(prefixesArg.split('=')[1].split(',').filter(Boolean)) : null;
const want = (p) => !only || only.has(p);

const today = new Date();

// 档案尚未填写（仅复制了模板）：跳过，不算失败
if (DOC_PREFIXES.every((p) => !docPath(p))) {
  console.log('档案漂移检查：尚未填写档案，跳过');
  process.exit(0);
}

// 状态文件：唯一的新鲜度事实源
const STATUS = join(ARCHIVE_DIR, 'status.json');
if (!existsSync(STATUS)) {
  if (DOC_PREFIXES.some((p) => want(p) && docPath(p))) {
    err('status.json', '缺失；按 _template/status.json 创建并填写复核状态');
  }
} else {
  let status = null;
  try {
    status = JSON.parse(read(STATUS));
  } catch {
    err('status.json', '无法解析');
  }
  if (status) {
    const entries = status.docs ?? {};
    for (const prefix of DOC_PREFIXES) {
      if (!want(prefix)) continue;
      if (!docPath(prefix)) continue;
      const entry = entries[prefix];
      if (!entry) {
        err(`${prefix}-状态`, 'status.json 缺少该档案条目');
        continue;
      }
      if (entry.status === '已复核') {
        if (!entry.reviewed) {
          err(`${prefix}-状态`, '已复核但缺少 reviewed 日期');
          continue;
        }
        const age = Math.floor((today - new Date(entry.reviewed + 'T00:00:00')) / 86400000);
        if (age > STALE_DAYS) err(`${prefix}-状态`, `已 ${age} 天未复核（阈值 ${STALE_DAYS} 天）`);
      } else if (entry.status === '待复核') {
        warn(`${prefix}-状态`, '标记为待复核');
      } else if (entry.status === '尚未成型') {
        if (!entry.note) warn(`${prefix}-状态`, '尚未成型需在 note 写明补齐触发点');
      } else {
        warn(`${prefix}-状态`, `未知状态值 ${entry.status}`);
      }
    }
    for (const key of Object.keys(entries)) {
      if (!DOC_PREFIXES.includes(key)) warn('status.json', `多余的档案条目 ${key}`);
    }
  }
}

// 按配置执行检查
for (const [prefix, spec] of Object.entries(docsCfg)) {
  if (!want(prefix)) continue;
  const f = docPath(prefix);
  if (!f) {
    err(prefix, '档案文件缺失');
    continue;
  }
  const name = prefix;
  for (const check of spec.checks ?? []) {
    const runner = CHECKERS[check.type];
    if (!runner) {
      warn(name, `未识别的检查类型 ${check.type}，跳过`);
      continue;
    }
    runner(f, check, name);
  }
}

/* ---------------- 汇总 ---------------- */

for (const w of warns) console.log(`WARN ${w}`);
for (const e of errors) console.log(`ERROR ${e}`);

if (errors.length === 0) {
  console.log(`档案漂移检查：通过（${warns.length} 处提示）`);
  process.exit(0);
}
console.log(`\n档案漂移检查：${errors.length} 处不一致、${warns.length} 处提示`);
console.log('修复：按证据更新对应档案，或在档案中写明差异依据。');
process.exit(1);
