// 检查器共用的通用工具：仓库根、受版本控制的文件、文本类型判定。
// 不包含任何具体项目的名称或路径。
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = dirname(fileURLToPath(import.meta.url));
export const ROOT = resolve(TOOLS_DIR, '..', '..');

const EXCLUDE_DIRS = new Set([
  'node_modules', '.git', '.hg', '.svn', 'target', 'dist', 'build', 'out',
  '.idea', '.vscode', 'coverage', '.gradle', '.mvn', '__pycache__',
]);

export const TEXT_EXT = new Set([
  '.java', '.kt', '.ts', '.tsx', '.js', '.mjs', '.cjs', '.vue', '.css', '.scss', '.less',
  '.md', '.txt', '.yml', '.yaml', '.json', '.xml', '.sql', '.sh', '.ps1', '.properties',
  '.html', '.htm', '.py', '.go', '.rs', '.rb', '.php', '.cs', '.gradle', '.toml', '.ini',
]);

export function isExcluded(relPath) {
  return relPath.split('/').some((seg) => EXCLUDE_DIRS.has(seg));
}

export function isText(relPath) {
  const dot = relPath.lastIndexOf('.');
  return dot >= 0 && TEXT_EXT.has(relPath.slice(dot).toLowerCase());
}

/** 仓库内受版本控制的文件（相对仓库根的 POSIX 路径）；无 git 时退化为遍历。 */
export function trackedFiles() {
  try {
    const out = execFileSync('git', ['ls-files', '-z'], {
      cwd: ROOT,
      encoding: 'utf8',
      maxBuffer: 128 * 1024 * 1024,
    });
    return out
      .split('\0')
      .filter(Boolean)
      .map((p) => p.replace(/\\/g, '/'))
      .filter((p) => !isExcluded(p));
  } catch {
    return walk('');
  }
}

function walk(relDir) {
  const out = [];
  const abs = relDir ? join(ROOT, relDir) : ROOT;
  let entries;
  try {
    entries = readdirSync(abs, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const rel = relDir ? `${relDir}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      if (EXCLUDE_DIRS.has(entry.name)) continue;
      out.push(...walk(rel));
    } else if (entry.isFile()) {
      out.push(rel);
    }
  }
  return out;
}


/**
 * 检查器配置：插件内置 default（config.json），宿主可在仓库根放 project.config.json 覆盖。
 * 宿主没有配置文件时按内置默认工作，不强制宿主提供任何文件。
 */
export function loadConfig() {
  const defaults = JSON.parse(readFileSync(join(TOOLS_DIR, 'config.json'), 'utf8'));
  const overridePath = join(ROOT, 'project.config.json');
  if (!existsSync(overridePath)) return defaults;
  try {
    return deepMerge(defaults, JSON.parse(readFileSync(overridePath, 'utf8')));
  } catch {
    return defaults;
  }
}

function deepMerge(base, patch) {
  if (Array.isArray(base) || typeof base !== 'object' || base === null) return patch;
  const out = { ...base };
  for (const [k, v] of Object.entries(patch ?? {})) {
    out[k] = k in base ? deepMerge(base[k], v) : v;
  }
  return out;
}

/** 递归收集目录下的 .vue 文件（绝对路径）。 */
export function walkVueFiles(dir) {
  const out = [];
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walkVueFiles(full));
    } else if (entry.name.endsWith('.vue')) {
      out.push(full);
    }
  }
  return out;
}

/** 下标所在行号（1 起）。 */
export function lineOf(content, index) {
  return content.slice(0, index).split('\n').length;
}

const CMD_PREFIX = /^(sh|node|cd|mvn|pnpm|npx|java|git|docker)\s+/;
const PATH_EXT = /\.(md|mjs|js|cjs|ts|vue|xml|yml|yaml|json|sh|ps1|bat|java|properties|sql|html|css)$/;

/**
 * 抽取文本中反引号串里的「仓库路径候选」，判定存在性由调用方完成。
 * 只看像路径的串：剥掉命令前缀后不含空白与 shell 字符、不以 `-` 或 `~` 开头。
 * @param {string} text 待扫描文本
 * @param {object} [options]
 * @param {boolean} [options.requireSlash] 只认含 `/` 的串（正文里的裸文件名不算指针）
 * @param {boolean} [options.requireExtension] 只认末段带已知文件扩展名的串（正文里的包路径片段不算指针）
 * @param {boolean} [options.skipElision] 跳过 `a/.../b` 这类省略写法
 * @param {boolean} [options.stripLinks] 先抹掉 Markdown 链接，避免把外链标签当仓库路径
 * @returns {string[]} 去重后的候选路径
 */
export function backtickPaths(
  text,
  { requireSlash = false, requireExtension = false, skipElision = false, stripLinks = false } = {},
) {
  const source = stripLinks ? text.replace(/\[[^\]]*]\([^)]*\)/g, ' ') : text;
  const out = new Set();
  for (const m of source.matchAll(/`([^`\n]+)`/g)) {
    const s = m[1].trim().replace(CMD_PREFIX, '');
    if (!s || /[\s(){}<>*=$"'|&;,:^\\]/.test(s) || s.startsWith('-') || s.startsWith('~')) continue;
    if (skipElision && s.includes('...')) continue;
    if (requireSlash && !s.includes('/')) continue;
    if (requireExtension && !PATH_EXT.test(s)) continue;
    if (s.includes('/') || PATH_EXT.test(s)) out.add(s);
  }
  return [...out];
}

