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
