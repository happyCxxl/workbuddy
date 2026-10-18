#!/usr/bin/env node
/**
 * 文件编码门禁：扫出被"用系统编码误读/误写"破坏过的源码与文档。
 *
 * 触发场景：用 PowerShell 的文本写入命令（Set-Content / Out-File 等）改 UTF-8 源码时，
 * 系统编码会把中文读成 GBK 再写回，文件里的中文变成 `锛?` / `鏂囦欢` 这类字符序列，
 * 或直接出现替换字符 U+FFFD。这类破坏不影响编译（字符串仍是合法内容），
 * 类型检查与 lint 都看不出来，只有人眼看界面或日志才会发现。
 *
 * 两道网：
 * 1. ERROR：出现替换字符 U+FFFD，或命中词表里的 GBK 误读序列；
 * 2. WARN：一行里出现两个以上"乱码特征字"（锛 鈥 鏄 鐨 涓 … 正常中文几乎不出现的字）。
 *    这一类抓的是精确词表抓不到的情形 —— 行内被吃掉一个字节（结尾变 ?），整行匹配必然失败。
 *    单字可能是真字，故要求一行至少两个，且先按 WARN 观察，确认无误报再收紧。
 *
 * 扫描范围含各模块 src、web、docs、tools、docker、sql，以及**仓库根与各模块的 pom.xml**。
 * 确需在文档里举例写出这些序列时，在该行写 `编码豁免：<理由>`。
 *
 * 退出码：有 ERROR 命中 → 1；只有 WARN 或全干净 → 0。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = process.cwd();

/** 扫描根：与注释门禁同一批根，另加 SQL 与容器编排目录 */
const SCAN_ROOTS = [
  'workbuddy-common/src',
  'workbuddy-infra/src',
  'workbuddy-auth/src',
  'workbuddy-file/src',
  'workbuddy-model/src',
  'workbuddy-vector/src',
  'workbuddy-worker/src',
  'workbuddy-biz/src',
  'web/src',
  'web/docs',
  'docs',
  '.agent/tools',
  '.agent',
  'docker',
  'sql',
  'README.md',
  '.husky',
];

/** 只看文本类文件 */
const TEXT_EXT = new Set([
  '.java', '.ts', '.vue', '.css', '.mjs', '.js', '.md', '.sql', '.yml', '.yaml',
  '.json', '.html', '.sh', '.xml', '.properties',
]);

/** 构建文件不入 SCAN_ROOTS（那里只列了各模块的 src），单独补一轮 */
const BUILD_FILES = [
  'pom.xml',
  'workbuddy-common/pom.xml',
  'workbuddy-infra/pom.xml',
  'workbuddy-auth/pom.xml',
  'workbuddy-file/pom.xml',
  'workbuddy-model/pom.xml',
  'workbuddy-vector/pom.xml',
  'workbuddy-worker/pom.xml',
  'workbuddy-biz/pom.xml',
];

/**
 * GBK 误读序列：正常中文写作里不会连着出现的组合（单字如「涓」「鏄」可能是真字，故用多字组合判定）。
 */
const MOJIBAKE = [
  '锛?', '锛屛', '鐨勶', '鏂囦欢', '涓€', '鍜屛', '鏄剧ず', '绗?', '娆℃', '鍏抽棴',
  '寮€濮', '鑰楁椂', '浠诲姟', '鏃堕棿', '澶辫触', '鎻愮ず', '瀵硅薄', '缁撴灉',
  '鏌ヨ', '杈撳叆', '杈撳嚭', '鍒嗘瀽', '鐭ヨ瘑', '搴旂敤', '鏈嶅姟', '鎺ュ彛',
  '澶勭悊', '鏋勫缓', '绱㈠紩', '鍚戦噺', '鍒囩墖', '棰勫', '瀛楁', '鏂囨。',
  '鍙傛暟', '宸ュ叿', '缂撳瓨', '妫€鏌', '閰嶇疆', '浠诲姟',
];

/** 乱码特征字：出现这些字（正常中文里极少见）说明该行可能是"GBK 误读"的残留 */
const MARKERS = new Set('锛鈥鏄鐨涓浣鏂鎴鏈绾鍜鎺鎵鏇鏉瀹閿鏍鍙缂缁缃鑺鑳鍔鐩鍧妫閰'.split(''));

/** 本文件自己必须写出这些序列作为判定依据，故跳过自身 */
const SELF = '.agent/tools/check-encoding.mjs';

const HITS = [];
const WARNS = [];
let scanned = 0;

function isExempt(line) {
  return line.includes('编码豁免：');
}

function markerCount(line) {
  let count = 0;
  for (const ch of line) {
    if (MARKERS.has(ch)) {
      count += 1;
    }
  }
  return count;
}

function scanFile(absPath) {
  const rel = relative(ROOT, absPath).split('\\').join('/');
  if (rel === SELF) {
    return;
  }
  let text;
  try {
    text = readFileSync(absPath, 'utf8');
  } catch {
    return;
  }
  scanned += 1;
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (isExempt(line)) {
      continue;
    }
    if (line.includes('\uFFFD')) {
      HITS.push({ file: relative(ROOT, absPath), line: i + 1, kind: '替换字符 U+FFFD' });
      continue;
    }
    const token = MOJIBAKE.find((m) => line.includes(m));
    if (token) {
      HITS.push({ file: relative(ROOT, absPath), line: i + 1, kind: `GBK 误读「${token}」` });
      continue;
    }
    if (markerCount(line) >= 2) {
      WARNS.push({ file: relative(ROOT, absPath), line: i + 1, kind: '疑似 GBK 误读（特征字 x2+）' });
    }
  }
}

function walk(target) {
  let st;
  try {
    st = statSync(target);
  } catch {
    return;
  }
  if (st.isFile()) {
    if (TEXT_EXT.has(target.slice(target.lastIndexOf('.')))) {
      scanFile(target);
    }
    return;
  }
  for (const entry of readdirSync(target)) {
    if (entry === 'node_modules' || entry === 'target' || entry === 'dist' || entry === '.git') {
      continue;
    }
    walk(join(target, entry));
  }
}

for (const root of SCAN_ROOTS) {
  walk(join(ROOT, root));
}
for (const build of BUILD_FILES) {
  walk(join(ROOT, build));
}

if (HITS.length === 0 && WARNS.length === 0) {
  console.log(`扫描文件 ${scanned} 个；未发现编码破坏 ✓`);
  process.exit(0);
}

if (HITS.length > 0) {
  console.log(`编码门禁命中 ${HITS.length} 处（文件被系统编码误读/误写过）：`);
  for (const hit of HITS) {
    console.log(`  ${hit.file}:${hit.line}: ${hit.kind}`);
  }
  console.log('  修法：用 UTF-8 工具（编辑器 / edit 工具）重写受影响的行；不要用 PowerShell 的 Set-Content 改源码。');
}
if (WARNS.length > 0) {
  console.log(`\n疑似编码破坏 ${WARNS.length} 处（特征字 x2+，先按 WARN 观察，逐条判断）：`);
  for (const warn of WARNS.slice(0, 30)) {
    console.log(`  ${warn.file}:${warn.line}: ${warn.kind}`);
  }
  if (WARNS.length > 30) {
    console.log(`  ... 其余 ${WARNS.length - 30} 处略`);
  }
}
console.log(`\n扫描文件 ${scanned} 个；ERROR ${HITS.length}、疑似 WARN ${WARNS.length}。`);
process.exit(HITS.length === 0 ? 0 : 1);
