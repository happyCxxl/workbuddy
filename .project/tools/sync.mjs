#!/usr/bin/env node
/**
 * 档案同步入口：`.project` 的唯一入口。
 *
 * 生成物目录：`.project/archive/`（档案 01–11 与 config.json）；本文件与模板、提示词同属通用部分。
 *
 * 模式：
 *   无参数        状态报告与待办清单（配置 / 档案 / 注册 / 漂移）
 *   --suggest     配置候选：扫描仓库给出候选与依据，只打印不写盘
 *   --gate        提交门禁：仅当暂存含命中档案管辖路径的代码改动时，要求同提交更新档案
 *
 * 退出码：--gate 未通过时为 1；其余为 0。
 */
import { execFileSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const TOOLS_DIR = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(TOOLS_DIR, '..', '..');
const PROJECT_DIR = join(ROOT, '.project');
const ARCHIVE_DIR = join(PROJECT_DIR, 'archive');
const CONFIG = join(ARCHIVE_DIR, 'config.json');
const VERIFIER = join(TOOLS_DIR, 'check-project-docs.mjs');
const DOC_PREFIXES = ['01', '02', '03', '04', '05', '06', '07', '08', '09', '10', '11'];

const SKIP_DIRS = new Set([
  'node_modules', 'target', 'dist', 'build', 'out', 'coverage', '.git', '.idea', '.gradle', '.mvn',
]);

const read = (p) => readFileSync(p, 'utf8');
const git = (args) => {
  try {
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim();
  } catch {
    return '';
  }
};

function walk(relDir, out = []) {
  const abs = join(ROOT, relDir);
  let entries;
  try {
    entries = readdirSync(abs, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    const rel = relDir ? `${relDir}/${e.name}` : e.name;
    if (e.isDirectory()) {
      if (!SKIP_DIRS.has(e.name)) walk(rel, out);
    } else {
      out.push(rel);
    }
  }
  return out;
}

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

function docPath(prefix) {
  if (!existsSync(ARCHIVE_DIR)) return null;
  const name = readdirSync(ARCHIVE_DIR).find((f) => f.startsWith(prefix) && f.endsWith('.md'));
  return name ? join(ARCHIVE_DIR, name) : null;
}

function loadConfig() {
  if (!existsSync(CONFIG)) return null;
  try {
    return JSON.parse(read(CONFIG));
  } catch {
    return null;
  }
}

/** 读取暂存区里的状态文件（提交前的版本）。 */
function readStagedStatus() {
  try {
    const raw = execFileSync('git', ['show', ':.project/archive/status.json'], {
      cwd: ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
    return raw.trim() ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

/* ---------------- 配置候选 ---------------- */

function suggest() {
  const files = walk('');
  const proposal = {};
  const evidence = [];
  const unknown = [];

  if (existsSync(join(ROOT, 'pom.xml'))) {
    const modules = [...read(join(ROOT, 'pom.xml')).matchAll(/<module>([^<]+)<\/module>/g)].map((m) => m[1].trim());
    if (modules.length > 0) {
      let p = modules[0];
      for (const n of modules) while (!n.startsWith(p)) p = p.slice(0, -1);
      const cut = p.lastIndexOf('-');
      const prefix = cut >= 0 ? p.slice(0, cut + 1) : '';
      const checks = [
        { type: 'modules', target: '模块', pom: 'pom.xml' },
        { type: 'edges', target: '依赖边', pom: 'pom.xml' },
        {
          type: 'dirs',
          target: '目录地图',
          ignore: ['.git', 'node_modules', 'target', 'dist', 'build', 'out', 'coverage', '.idea', '.gradle'],
        },
      ];
      if (prefix) {
        checks[0].stripPrefix = prefix;
        checks[1].modulePrefix = prefix;
      }
      proposal['05'] = { watch: ['pom.xml', '*/pom.xml'], checks };
      evidence.push(`构建：pom.xml 声明 ${modules.length} 个模块（公共前缀 ${prefix || '无'}）`);
    }
  } else {
    unknown.push('未发现 pom.xml：若使用其它构建工具，请改写 05 的 modules / edges 检查或删除');
  }

  const controllers = files.filter((f) => {
    if (!f.endsWith('Controller.java')) return false;
    return /@RequestMapping\("/.test(read(join(ROOT, f)));
  });
  if (controllers.length > 0) {
    proposal['07'] = {
      watch: ['*/src/main/java/**/*Controller.java'],
      checks: [
        {
          type: 'annotations',
          target: '对外接口',
          glob: '*/src/main/java/**/*Controller.java',
          pattern: '@RequestMapping\\("([^"]+)"\\)',
          stripSuffix: '/*',
        },
      ],
    };
    evidence.push(`接口：${controllers.length} 个 Controller 带路径注解`);
  }

  const mappers = files.filter((f) => f.endsWith('Mapper.java'));
  if (mappers.length > 0) {
    proposal['06'] = {
      watch: ['*/src/main/java/**/*Mapper.java'],
      checks: [
        { type: 'files', target: '数据模型概览', glob: '*/src/main/java/**/*Mapper.java', suffix: 'Mapper', ignore: [] },
      ],
    };
    evidence.push(`数据：${mappers.length} 个 Mapper（按文件名去后缀推导清单）`);
  }

  const compose = files.find((f) => /(^|\/)(docker-)?compose\.ya?ml$/.test(f));
  if (compose) {
    proposal['08'] = {
      watch: [`${dirname(compose).replace(/\\/g, '/')}/**`],
      checks: [{ type: 'services', file: compose }],
    };
    evidence.push(`编排：${compose}`);
  }

  const tests = files.filter((f) => /\/src\/test\/java\//.test(f) && f.endsWith('.java'));
  if (tests.length > 0) {
    proposal['11'] = {
      watch: [],
      checks: [{ type: 'count', glob: '**/src/test/java/**/*.java', pattern: '(\\d+)\\s*个测试类' }],
    };
    evidence.push(`测试：${tests.length} 个测试类文件`);
  }

  if (existsSync(join(ROOT, '.git'))) {
    proposal['01'] = { watch: [], checks: [{ type: 'git-timeline' }] };
    evidence.push('版本库：检测到 .git，可核对时间线与远端地址');
  }

  const ordered = {};
  for (const key of Object.keys(proposal).sort()) ordered[key] = proposal[key];

  console.log('=== 配置候选（写入 .project/archive/config.json）===');
  console.log(JSON.stringify({ docs: ordered }, null, 2));
  console.log('\n=== 检测依据 ===');
  for (const line of evidence) console.log(`- ${line}`);
  if (unknown.length > 0) {
    console.log('\n=== 未能推断 ===');
    for (const line of unknown) console.log(`- ${line}`);
  }
  console.log('\n提示：以上仅为候选；确认 watch 覆盖、target 小节名与提取器适用性后，再写入配置。');
}

/* ---------------- 提交门禁 ---------------- */

function gate() {
  const config = loadConfig();
  if (!config) {
    console.log('档案同步检查：未通过——尚未接入（缺少 archive/config.json）');
    console.log('补全：运行 `node .project/tools/sync.mjs --suggest` 取得候选，');
    console.log('      与用户确认后写入 .project/archive/config.json；');
    console.log('      0→1 项目可写入空配置 {"docs": {}} 表示暂不检查。');
    process.exit(1);
  }
  if (Object.keys(config.docs ?? {}).length === 0) {
    console.log('档案同步检查：配置为空，跳过');
    process.exit(0);
  }

  let staged = [];
  try {
    staged = git(['-c', 'core.quotePath=false', 'diff', '--cached', '--name-only', '--diff-filter=ACMR'])
      .split(/\r?\n/)
      .map((s) => s.trim())
      .filter(Boolean);
  } catch {
    process.exit(0);
  }
  if (staged.length === 0) process.exit(0);

  const errors = [];
  const synced = [];
  const head = git(['rev-parse', '--short', 'HEAD']);
  const stagedStatus = readStagedStatus();
  const statusStaged = staged.includes('.project/archive/status.json');

  for (const [prefix, entry] of Object.entries(config.docs ?? {})) {
    const patterns = entry.watch ?? [];
    if (patterns.length === 0) continue;
    const regexes = patterns.map(globToRegex);
    const hits = staged.filter((file) => regexes.some((re) => re.test(file)));
    if (hits.length === 0) continue;

    const shown = hits.slice(0, 3).join('、');
    const more = hits.length > 3 ? ' 等' : '';
    if (!docPath(prefix)) {
      errors.push(`配置声明了 ${prefix} 的管辖路径（${shown}${more}），但 archive 中缺少 ${prefix} 档案；请先按 _template 补齐`);
      continue;
    }
    if (!statusStaged) {
      errors.push(`.project/archive/status.json：本次提交改动了 ${prefix} 管辖的路径（${shown}${more}），但未在同提交更新状态文件`);
      continue;
    }
    const statusEntry = stagedStatus?.docs?.[prefix];
    if (!statusEntry || statusEntry.baseline !== head) {
      errors.push(`.project/archive/status.json：${prefix} 的复核基线应为 ${head}（当前 ${statusEntry?.baseline ?? '空'}）`);
    } else {
      synced.push(prefix);
    }
  }

  if (errors.length > 0) {
    console.log('档案同步检查：未通过');
    for (const e of errors) console.log(`ERROR ${e}`);
    console.log('\n修复：按 .project/prompts/sync.md 更新对应档案后重新提交。');
    process.exit(1);
  }

  if (synced.length > 0) {
    console.log(`档案同步检查：${synced.join('、')} 已随代码更新，执行结构化验证`);
    execFileSync('node', [VERIFIER, `--prefixes=${synced.join(',')}`], { cwd: ROOT, stdio: 'inherit' });
  }
  console.log('档案同步检查：通过');
}

/* ---------------- 状态报告 ---------------- */

function report() {
  const lines = ['档案同步状态'];
  const todos = [];

  const config = loadConfig();
  if (config) {
    const count = Object.keys(config.docs ?? {}).length;
    lines.push(`- 配置：存在（${count} 份档案声明）`);
  } else {
    lines.push('- 配置：缺失');
    todos.push('运行 `node .project/tools/sync.mjs --suggest` 取得候选，按 `prompts/sync.md` 与用户确认后写入 `.project/archive/config.json`');
  }

  const present = DOC_PREFIXES.filter((p) => docPath(p));
  const missing = DOC_PREFIXES.filter((p) => !docPath(p));
  lines.push(`- 档案：已填 ${present.length}/${DOC_PREFIXES.length}${missing.length ? `（缺 ${missing.join('、')}）` : ''}`);
  if (missing.length > 0) {
    todos.push(`按 \`.project/_template/\` 补齐档案 ${missing.join('、')}，写入 \`.project/archive/\``);
  }

  const statusPath = join(ARCHIVE_DIR, 'status.json');
  if (existsSync(statusPath)) {
    try {
      const entries = Object.values(JSON.parse(read(statusPath)).docs ?? {});
      const reviewed = entries.filter((e) => e.status === '已复核').length;
      const pending = entries.filter((e) => e.status === '待复核').length;
      const unformed = entries.filter((e) => e.status === '尚未成型').length;
      lines.push(`- 状态：已复核 ${reviewed}、待复核 ${pending}、尚未成型 ${unformed}`);
      if (pending > 0) todos.push('更新「待复核」的档案，并在 `archive/status.json` 中改回「已复核」');
    } catch {
      lines.push('- 状态：status.json 解析失败');
      todos.push('修复 `.project/archive/status.json` 的 JSON 格式');
    }
  } else {
    lines.push('- 状态：status.json 缺失');
    if (present.length > 0) todos.push('按 `_template/status.json` 创建 `archive/status.json` 并填写复核状态');
  }

  const agentsPath = join(ROOT, 'AGENTS.md');
  const registered = existsSync(agentsPath) && read(agentsPath).includes('.project/');
  lines.push(`- 注册：${registered ? 'AGENTS.md 已指向 .project' : '未注册'}`);
  if (!registered && present.length > 0) {
    todos.push('按 `prompts/sync.md` 第五节，在 `AGENTS.md` 追加一行指针（只追加，不改写）');
  }

  if (config && present.length > 0) {
    try {
      execFileSync('node', [VERIFIER], { cwd: ROOT, stdio: 'inherit' });
      lines.push('- 漂移：通过');
    } catch {
      lines.push('- 漂移：存在问题（详见上方输出）');
      todos.push('按证据修正档案（`prompts/sync.md` 的更新规则），再跑本命令复核');
    }
  } else {
    lines.push('- 漂移：未检查（配置或档案未就绪）');
  }

  console.log(lines.join('\n'));
  if (todos.length === 0) {
    console.log('\n待办：无');
  } else {
    console.log('\n待办：');
    todos.forEach((t, i) => console.log(`${i + 1}. ${t}`));
  }
}

/* ---------------- 入口分发 ---------------- */

const args = process.argv.slice(2);
if (args.includes('--suggest')) {
  suggest();
} else if (args.includes('--gate')) {
  gate();
} else {
  report();
}
