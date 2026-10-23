#!/usr/bin/env node
// Java 侧的验证档位编排：按改动范围调用检查器与 Maven 目标，透传子进程退出码。
// 通用原则见 .agent/standards/common/08-验证节奏.md，本脚本是 Maven 构建下的落地实现；
// 只做编排，不改变任何检查器的判定口径，也不提供「跳过某项检查」的开关——
// 哪一档包含哪些检查，由构建与门禁自身的阶段绑定决定。
import { spawnSync } from 'node:child_process';
import { closeSync, existsSync, openSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import process from 'node:process';
import { ROOT } from '../lib.mjs';

const IS_WIN = process.platform === 'win32';
const BATCH = 40;
const USAGE = [
  '用法：',
  '  node .agent/tools/java/verify.mjs fast [文件...]            检查器档：按改动文件触发的检查器（中间产物、编码、文档结构、规范边界、入口指针、模块结构、Java 形态、注释、用例质量）',
  '  node .agent/tools/java/verify.mjs tests <模块> --test=<类>  定向用例档：单个用例类或方法',
  '  node .agent/tools/java/verify.mjs module <模块>             模块档：该模块全部用例，含依赖链编译',
  '  node .agent/tools/java/verify.mjs full                      全量档：整仓依赖链 + 静态检查 + 覆盖率',
  '',
  '选项：',
  '  --clean      清理式重建；仅构建配置或依赖版本变化时使用（见 COMMON-VERIFY-02）',
  '  --verbose    不传 -q，保留 Maven 全量输出',
].join('\n');

function die(message) {
  console.error(message);
  console.error('');
  console.error(USAGE);
  process.exit(2);
}

/** 子进程执行；capture 时输出落临时文件（不经管道），只回显尾部。返回退出码（不吞）。 */
function run(cmd, args, { capture = false, verbose = false } = {}) {
  const started = Date.now();
  const log = join(tmpdir(), `dsh-verify-${Date.now()}-${process.pid}.log`);
  let res;
  if (capture && !verbose) {
    const fd = openSync(log, 'w');
    try {
      res = spawnSync(cmd, args, { cwd: ROOT, stdio: ['ignore', fd, fd], shell: IS_WIN });
    } finally {
      closeSync(fd);
    }
  } else {
    res = spawnSync(cmd, args, { cwd: ROOT, stdio: 'inherit', shell: IS_WIN });
  }
  const sec = ((Date.now() - started) / 1000).toFixed(1);
  const status = res.status === null ? 1 : res.status;
  if (status !== 0) {
    console.error('');
    console.error(`✘ 失败（${sec}s，退出码 ${status}）：${cmd} ${args.join(' ')}`);
    if (capture && !verbose) {
      console.error(`  最后 40 行（完整日志：${log}）：`);
      console.error(readFileSync(log, 'utf8').split(/\r?\n/).slice(-40).join('\n'));
    }
  }
  return { status, sec, log: capture && !verbose ? log : '' };
}

function chunk(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}

/** 自 HEAD 以来的改动文件：已跟踪的 diff 与未跟踪的新文件。用 -z 取原始路径，避免非 ASCII 路径被转义。 */
function changedFiles() {
  const git = (args) => {
    const res = spawnSync('git', [...args, '-z'], { cwd: ROOT, encoding: 'utf8' });
    if (res.status !== 0) return [];
    return (res.stdout || '').split('\0').filter(Boolean);
  };
  const all = [...git(['diff', '--name-only', 'HEAD']), ...git(['ls-files', '--others', '--exclude-standard'])];
  return [...new Set(all.map((p) => p.replace(/\\/g, '/')))].filter((p) => existsSync(join(ROOT, p)));
}

/** 检查器档：按扩展名分流，任一检查器非零即停。 */
function fast(files, opts) {
  const targets = files.length ? files : changedFiles();
  if (targets.length === 0) {
    console.log('检查器档：自 HEAD 以来没有改动文件，跳过');
    return 0;
  }
  const java = targets.filter((f) => f.endsWith('.java'));
  const docs = targets.filter((f) => f.endsWith('.md'));
  // 只把 web/ 下的前端产物交给 pnpm lint；.mjs 工具脚本不算前端改动。
  const web = targets.filter((f) => f.startsWith('web/') && /\.(ts|tsx|js|mjs|cjs|vue|css|scss|less)$/.test(f));
  const text = targets.filter((f) => /\.(java|kt|ts|tsx|js|mjs|cjs|vue|css|scss|md|yml|yaml|json|xml|sql|sh|ps1|properties|html)$/.test(f));

  const steps = [];
  if (text.length) {
    steps.push(['中间产物', [join(ROOT, '.agent/tools/common/check-artifacts.mjs')]]);
    steps.push(['编码完整性', [join(ROOT, '.agent/tools/common/check-encoding.mjs')]]);
  }
  if (docs.length) steps.push(['文档结构', [join(ROOT, '.agent/tools/common/check-docs.mjs')]]);
  if (targets.some((f) => f.startsWith('.agent/'))) {
    steps.push(['规范边界', [join(ROOT, '.agent/tools/common/check-scope.mjs')]]);
  }
  if (targets.some((f) => f.endsWith('AGENTS.md'))) {
    steps.push(['入口指针', [join(ROOT, '.agent/tools/common/check-agents.mjs')]]);
  }
  if (targets.some((f) => f.endsWith('pom.xml'))) {
    steps.push(['模块结构', [join(ROOT, '.agent/tools/common/check-modules.mjs')]]);
  }
  const testJava = targets.filter((f) => /src\/test\/.*\.java$/.test(f));
  for (const batch of chunk(testJava, BATCH)) {
    steps.push([`用例质量（${batch.length} 个文件）`, [join(ROOT, '.agent/tools/java/check-test-quality.mjs'), ...batch]]);
  }
  for (const batch of chunk(java, BATCH)) {
    steps.push([`Java 形态（${batch.length} 个文件）`, [join(ROOT, '.agent/tools/java/check-java-conventions.mjs'), ...batch]]);
    steps.push([`Javadoc（${batch.length} 个文件）`, [join(ROOT, '.agent/tools/java/check-javadoc.mjs'), ...batch]]);
  }

  for (const [label, args] of steps) {
    console.log(`>>>> 检查器：${label}`);
    const { status, sec } = run(process.execPath, args);
    if (status !== 0) {
      console.error(`检查器档失败：${label}`);
      return status;
    }
    if (opts.verbose) console.log(`     通过（${sec}s）`);
  }

  if (web.length) {
    console.log(`\n前端改动 ${web.length} 个文件：请另跑 pnpm -C web lint（分钟级，属模块档，不在本档内联跑）`);
  }
  console.log(`\n✔ 检查器档通过（${targets.length} 个改动文件）`);
  return 0;
}

/** Maven 档：tests / module / full 共用同一套参数拼装。 */
function maven(kind, module, opts, testPattern) {
  const goals = opts.clean ? ['clean'] : [];
  const base = ['-B', '-o', ...(opts.verbose ? [] : ['-q'])];
  // 测试内的应用日志改写到目标目录的 *-output.txt，控制台只留 Maven 与用例结论；
  // 失败时按明细查 target/surefire-reports 下的同名报告（COMMON-VERIFY-04：输出落文件，退出码定成败）。
  const quiet = ['-Dsurefire.redirectTestOutputToFile=true'];
  let args;
  if (kind === 'full') {
    args = [...base, ...goals, ...quiet, 'verify'];
  } else if (!module) {
    die('缺少模块名：module/tests 档必须指定模块');
    return 2;
  } else if (kind === 'tests') {
    if (!testPattern) {
      die('缺少 --test=<类或模式>：定向用例档必须指定用例');
      return 2;
    }
    args = [...base, ...goals, ...quiet, '-pl', module, '-am', 'test', `-Dtest=${testPattern}`, '-Dsurefire.failIfNoSpecifiedTests=false'];
  } else {
    args = [...base, ...goals, ...quiet, '-pl', module, '-am', 'test'];
  }

  const label = { fast: '检查器档', tests: '定向用例档', module: '模块档', full: '全量档' }[kind];
  console.log(`>>>> ${label}：mvn ${args.join(' ')}`);
  const { status, sec, log } = run('mvn', args, { capture: true, verbose: opts.verbose });
  if (status !== 0) {
    console.error(`\n${label}失败（${sec}s）。按 COMMON-VERIFY-03 只重跑失败所在的那一层，不要整套重来。`);
    return status;
  }
  console.log(`\n✔ ${label}通过（${sec}s）` + (log ? `；日志 ${log}` : ''));
  if (kind === 'full') console.log('推送前另跑 sh .agent/tools/common/pre-push-gate.sh，覆盖 Maven 之外的检查。');
  return 0;
}

function main(argv) {
  const opts = { clean: argv.includes('--clean'), verbose: argv.includes('--verbose') };
  const rest = argv.filter((a) => a !== '--clean' && a !== '--verbose');
  const command = rest[0];
  const testFlag = rest.find((a) => a.startsWith('--test='));

  switch (command) {
    case 'fast':
      process.exit(fast(rest.slice(1), opts));
      break;
    case 'tests':
      process.exit(maven('tests', rest[1], opts, testFlag ? testFlag.slice('--test='.length) : ''));
      break;
    case 'module':
      process.exit(maven('module', rest[1], opts));
      break;
    case 'full':
      process.exit(maven('full', null, opts));
      break;
    default:
      die(command ? `未知子命令：${command}` : '缺少子命令');
  }
}

main(process.argv.slice(2));
