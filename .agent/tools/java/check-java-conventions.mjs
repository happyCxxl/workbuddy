#!/usr/bin/env node
// Java 形态检查：把 Java 侧的形态规则落成可执行判定（注释覆盖与格式由 check-javadoc 负责）。
// 通用约定：扫描仓库内受版本控制的 .java 文件；角色由包路径决定，不写死任何项目名称。
// 用法：
//   node .agent/tools/java/check-java-conventions.mjs             # 全量审计
//   node .agent/tools/java/check-java-conventions.mjs a.java ...   # 只查指定文件（门禁按改动范围调用）
import { existsSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import process from 'node:process';
import { ROOT, trackedFiles } from '../lib.mjs';

// 契约包内禁止出现的后缀（JAVA-NAME-002）
const CONTRACT_FORBIDDEN = ['Request', 'Response', 'View', 'Filter'];

// 数据访问接口后缀：入口层不得直接依赖（JAVA-LAYER-002）
const DATA_SUFFIX = /(Mapper|DbService)$/;

const HITS = [];
const error = (file, line, rule, msg) => HITS.push({ level: 'ERROR', file, line, rule, msg });

/**
 * 把块注释整体抹成空格，保留换行与行数。
 * 用于让逐行判定不误报注释里出现的代码片段（例如 Javadoc 里的 {@code @Transactional}）。
 * @param {string} text 文件全文
 * @returns {string} 抹掉块注释后的全文
 */
function blankBlockComments(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '));
}

/**
 * 判定类所在的角色位置对应的强制后缀（JAVA-NAME-001）。
 * 只认包路径：位置决定角色，角色决定后缀。返回 null 表示该位置无强制后缀。
 * @param {string} file 仓库内相对路径，已统一为正斜杠
 * @returns {string|null} 强制后缀
 */
function roleSuffix(file) {
  const f = `/${file}`;
  if (/\/service\/db\/impl\//.test(f)) return 'DbServiceImpl';
  if (/\/service\/db\/[^/]+\.java$/.test(f)) return 'DbService';
  if (/\/service\/impl\//.test(f)) return 'ServiceImpl';
  if (/\/service\/[^/]+\.java$/.test(f)) return 'Service';
  if (/\/controller\//.test(f)) return 'Controller';
  if (/\/mapper\//.test(f)) return 'Mapper';
  if (/\/dto\/request\//.test(f)) return 'DTO';
  if (/\/dto\/response\//.test(f)) return 'VO';
  return null;
}

/**
 * 测试类判定：含测试方法才算测试类（JAVA-TEST-001）。
 * 测试支撑类（夹具、构造器、替身工具）没有测试方法，不受 Test 后缀约束。
 * @param {string} code 抹掉块注释后的源码
 * @returns {boolean} 是否含测试方法
 */
function hasTestMethods(code) {
  return /@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b/.test(code);
}

/** 源码根目录 → 该根下的类名集合，用于查找同名接口（JAVA-NAME-003）。 */
const classNamesByRoot = new Map();

/**
 * 取文件所在源码根下全部类名。源码根指到 src/{main,test}/java 为止。
 * @param {string} file 仓库内相对路径
 * @returns {Set<string>} 该类名集合
 */
function siblingClassNames(file) {
  const m = /^(.*?\/src\/(?:main|test)\/java)\//.exec(file);
  if (!m) return new Set();
  const root = m[1];
  if (!classNamesByRoot.has(root)) {
    const names = new Set(
      trackedFiles()
        .filter((f) => f.startsWith(`${root}/`) && f.endsWith('.java'))
        .map((f) => basename(f, '.java')),
    );
    classNamesByRoot.set(root, names);
  }
  return classNamesByRoot.get(root);
}

function classLine(lines) {
  for (let i = 0; i < lines.length; i += 1) {
    if (/^\s*(public\s+)?(final\s+)?(abstract\s+)?(class|interface|enum|record)\s+\w+/.test(lines[i])) {
      return i + 1;
    }
  }
  return 0;
}

/**
 * 检查单个 Java 文件。
 * @param {string} file 仓库内相对路径
 */
function checkFile(file) {
  const abs = join(ROOT, file);
  if (!existsSync(abs)) return;
  const text = readFileSync(abs, 'utf8');
  const lines = text.split(/\r?\n/);
  const code = blankBlockComments(text).split(/\r?\n/);
  const name = basename(file, '.java');
  if (name === 'package-info' || name === 'module-info') return;

  const inTest = /\/src\/test\//.test(file);
  const line = classLine(lines);

  code.forEach((l, i) => {
    if (/^\s*\/\//.test(l)) return;
    if (/System\.(out|err)\s*\./.test(l)) error(file, i + 1, 'JAVA-LOG-001', '禁止 System.out / System.err');
    if (/\.printStackTrace\s*\(/.test(l)) error(file, i + 1, 'JAVA-LOG-001', '禁止 printStackTrace');
  });

  if (inTest) {
    if (hasTestMethods(code.join('\n')) && !name.endsWith('Test')) {
      error(file, line || 1, 'JAVA-TEST-001', '含测试方法的类必须以 Test 结尾');
    }
    return;
  }

  let contractHit = false;
  if (/\/dto\//.test(`/${file}`)) {
    for (const s of CONTRACT_FORBIDDEN) {
      if (name.endsWith(s)) {
        error(file, line || 1, 'JAVA-NAME-002', `契约包内不得使用 ${s} 后缀：请求用 DTO、响应用 VO`);
        contractHit = true;
      }
    }
    if (/Dto$/.test(name)) {
      error(file, line || 1, 'JAVA-NAME-002', '请求契约后缀必须全大写 DTO');
      contractHit = true;
    }
  }

  if (!contractHit) {
    const expected = roleSuffix(file);
    if (expected && !name.endsWith(expected)) {
      error(file, line || 1, 'JAVA-NAME-001', `该位置必须用 ${expected} 后缀：${name}`);
    }
  }

  if (name.endsWith('ServiceImpl')) {
    const iface = name.replace(/Impl$/, '');
    if (!siblingClassNames(file).has(iface)) {
      error(file, line || 1, 'JAVA-NAME-003', `缺少同名接口 ${iface}.java`);
    }
  }

  if (name.endsWith('Controller')) {
    code.forEach((l, i) => {
      if (/^\s*\/\//.test(l)) return;
      // 字节流接口允许用框架响应实体表达响应头，只约束返回 JSON 的方法（JAVA-DTO-004）
      const m = /\bpublic\s+(?:static\s+)?ResponseEntity\s*</.exec(l);
      if (m) {
        const rest = l.slice(m.index + m[0].length);
        if (!/^\s*(byte\s*\[\]|Resource|InputStreamResource|StreamingResponseBody|InputStream)(?=[\s>,])/.test(rest)) {
          error(file, i + 1, 'JAVA-DTO-004', 'JSON 接口返回统一响应外壳，不用 ResponseEntity');
        }
      }
      if (/\bcatch\s*\(/.test(l)) error(file, i + 1, 'JAVA-ERR-003', '入口层不捕获异常');
    });
    for (let i = 0; i < code.length; i += 1) {
      if (!/@RequestBody\b/.test(code[i])) continue;
      const window = code.slice(Math.max(0, i - 3), i + 3).join('\n');
      if (!/@Valid\b/.test(window)) error(file, i + 1, 'JAVA-DTO-002', '@RequestBody 参数缺少校验注解');
    }
    code.forEach((l, i) => {
      const m = /^\s*import\s+(?:static\s+)?([\w.]+);/.exec(l);
      if (m && DATA_SUFFIX.test(m[1].split('.').pop())) {
        error(file, i + 1, 'JAVA-LAYER-002', `入口层不得直接依赖数据访问：${m[1]}`);
      }
    });
  }

  code.forEach((l, i) => {
    if (!/@Transactional\b/.test(l)) return;
    const next = code[i + 1] ?? '';
    if (/^\s*(public\s+)?(final\s+)?(abstract\s+)?(class|interface)\s/.test(next)) {
      error(file, i + 1, 'JAVA-TX-001', '事务注解不得标在类上');
    } else if (!/\bpublic\b/.test(next)) {
      error(file, i + 1, 'JAVA-TX-001', '事务注解只允许标在 public 方法上');
    }
  });
}

const args = process.argv.slice(2);
const files = args.length > 0
  ? args.map((p) => p.replace(/\\/g, '/'))
  : trackedFiles().filter((f) => f.endsWith('.java'));

for (const f of files) checkFile(f);

const errors = HITS.filter((h) => h.level === 'ERROR');
if (errors.length === 0) {
  console.log(`Java 形态检查：通过（${files.length} 个文件）`);
  process.exit(0);
}
for (const h of errors) console.log(`${h.level} ${h.file}:${h.line} [${h.rule}] ${h.msg}`);
console.log('');
console.log(`Java 形态检查：扫描 ${files.length} 个文件，ERROR ${errors.length}`);
process.exit(1);
