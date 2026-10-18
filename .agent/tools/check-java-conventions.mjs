#!/usr/bin/env node
// Java 实现形态检查：把 Java 侧的形态规则落成可执行判定。
// 用法：
//   node .agent/tools/check-java-conventions.mjs            # 全量扫描（人工审计用）
//   node .agent/tools/check-java-conventions.mjs a.java b.java  # 只查指定文件（门禁按改动范围调用）
// 退出码：存在 ERROR 时 1。
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const MODULES = readdirSync(ROOT, { withFileTypes: true })
  .filter((d) => d.isDirectory() && /^workbuddy-[a-z]+$/.test(d.name))
  .map((d) => d.name);

// 后缀词表：与 .agent/standards/java/03-命名与类职责.md §1 保持一致
const SUFFIXES = [
  'Controller', 'Service', 'ServiceImpl', 'DbService', 'DbServiceImpl', 'Mapper',
  'DTO', 'VO', 'Assembler', 'Support', 'Helper', 'Handler', 'Provider', 'Registry',
  'Resolver', 'Runner', 'Config', 'Properties', 'Util', 'Exception', 'Enum',
  'Strategy', 'Context', 'Result', 'Info', 'Processor', 'Record', 'Snapshot',
  'Factory', 'Builder', 'Listener', 'Client', 'Consumer', 'Query', 'Param',
];
const FORBIDDEN_SUFFIX = ['Request', 'Response', 'View', 'Filter'];
const CONTROLLER_IMPORT_ALLOW = [
  'java.', 'jakarta.', 'org.springframework.', 'lombok.', 'io.swagger.',
  'com.workbuddy.common.', 'com.baomidou.mybatisplus.', 'org.slf4j.',
];

const HITS = [];
const warn = (file, line, rule, msg) => HITS.push({ level: 'WARN', file, line, rule, msg });
const error = (file, line, rule, msg) => HITS.push({ level: 'ERROR', file, line, rule, msg });

function walk(dir, out = []) {
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.name.endsWith('.java')) out.push(full);
  }
  return out;
}

function allJavaFiles() {
  const files = [];
  for (const m of MODULES) {
    walk(join(ROOT, m, 'src', 'main', 'java'), files);
    walk(join(ROOT, m, 'src', 'test', 'java'), files);
  }
  return files;
}

const rel = (f) => f.slice(ROOT.length + 1).replace(/\\/g, '/');

/** 只取顶层类型声明所在的那一行 */
function classLine(lines) {
  for (let i = 0; i < lines.length; i += 1) {
    if (/^\s*(public\s+)?(final\s+)?(abstract\s+)?(class|interface|enum|record)\s+\w+/.test(lines[i])) {
      return i + 1;
    }
  }
  return 0;
}

function hasClassJavadoc(lines) {
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i].trim();
    if (line === '') continue;
    if (line.startsWith('package ') || line.startsWith('import ')) continue;
    if (line.startsWith('/*') || line.startsWith('//')) {
      // 找到第一段注释，若在类声明前且形如 /** ... */ 即视为类注释
      for (let j = i; j < Math.min(lines.length, i + 60); j += 1) {
        if (/\*\//.test(lines[j])) return lines[i].includes('/**');
        if (/class\s+\w+/.test(lines[j])) return false;
      }
      return false;
    }
    return false;
  }
  return false;
}

function checkFile(file) {
  const text = readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/);
  const name = basename(file, '.java');
  const r = rel(file);
  const inTest = r.includes('/src/test/java/');
  const module = r.split('/')[0];

  if (name === 'package-info' || name === 'module-info') return;

  // 通用规则（主代码与测试都适用）
  lines.forEach((line, i) => {
    if (/@author\b/.test(line)) error(r, i + 1, 'JAVA-DOC-004', '注释不得标注 @author');
    if (/System\.(out|err)\s*\./.test(line)) error(r, i + 1, 'JAVA-LOG-001', '禁止 System.out / System.err');
    if (/\.printStackTrace\s*\(/.test(line)) error(r, i + 1, 'JAVA-LOG-001', '禁止 printStackTrace');
  });

  // LAYER-005：单文件 ≤400 行
  if (lines.length > 400) error(r, 1, 'JAVA-LAYER-005', `单文件 ${lines.length} 行，超过 400 行上限`);

  if (inTest) {
    // TEST-001：测试类命名
    if (!name.endsWith('Test')) error(r, classLine(lines) || 1, 'JAVA-TEST-001', '测试类必须以 Test 结尾');
    return;
  }

  const line = classLine(lines);

  // DOC-001：类级 Javadoc
  if (name !== 'package-info' && !hasClassJavadoc(lines)) {
    error(r, line || 1, 'JAVA-DOC-001', '缺少类级 Javadoc');
  }

  // NAME-002：禁用的请求/响应后缀
  for (const s of FORBIDDEN_SUFFIX) {
    if (name.endsWith(s)) error(r, line || 1, 'JAVA-NAME-002', `禁止使用 ${s} 后缀，请求用 DTO、响应用 VO`);
  }
  if (/Dto$/.test(name)) error(r, line || 1, 'JAVA-NAME-002', '请求对象后缀必须全大写 DTO');

  // NAME-001：后缀取自语表
  const isEntity = /^Wb[A-Z]/.test(name);
  const hasSuffix = SUFFIXES.some((s) => name.endsWith(s));
  const isAbstractBase = /^(Abstract|Base)/.test(name);
  if (!isEntity && !hasSuffix && !isAbstractBase) {
    error(r, line || 1, 'JAVA-NAME-001', `类名后缀不在词表内：${name}`);
  }

  // NAME-004：DbService 一表一个，命名 Wb<表名>DbService
  if (name.endsWith('DbService') || name.endsWith('DbServiceImpl')) {
    const base = name.replace(/Impl$/, '');
    if (!/^Wb[A-Z]/.test(base)) {
      error(r, line || 1, 'JAVA-NAME-004', 'DbService 必须命名为 Wb<表名>DbService');
    }
  }

  // NAME-005：Helper 只在 worker
  if (name.endsWith('Helper') && module !== 'workbuddy-worker') {
    error(r, line || 1, 'JAVA-NAME-005', 'Helper 只允许出现在 worker 模块，biz 层用 Support');
  }

  // NAME-003：ServiceImpl 必须有同名 Service 接口
  if (name.endsWith('ServiceImpl')) {
    const iface = name.replace(/Impl$/, '.java');
    const ifacePath = resolve(dirname(file), '..', iface);
    if (!existsSync(ifacePath)) {
      error(r, line || 1, 'JAVA-NAME-003', `缺少同名接口 ${iface}`);
    }
  }

  // DTO-001：请求/响应落位
  const pkgPath = r.split('/src/main/java/')[1] ?? '';
  if (name.endsWith('DTO') && !pkgPath.startsWith('com/workbuddy/common/dto/request/')) {
    error(r, line || 1, 'JAVA-DTO-001', '请求对象必须放在 common/dto/request/<域>');
  }
  if (name.endsWith('VO') && !pkgPath.startsWith('com/workbuddy/common/dto/response/')) {
    error(r, line || 1, 'JAVA-DTO-001', '响应对象必须放在 common/dto/response/<域>');
  }

  // DTO-002：@RequestBody 必须配 @Valid；DTO-004：禁止 ResponseEntity；ERR-003：Controller 不捕获异常
  if (name.endsWith('Controller')) {
    for (let i = 0; i < lines.length; i += 1) {
      if (!/@RequestBody\b/.test(lines[i])) continue;
      const window = lines.slice(Math.max(0, i - 3), i + 3).join('\n');
      if (!/@Valid\b/.test(window)) error(r, i + 1, 'JAVA-DTO-002', '@RequestBody 参数缺少 @Valid');
    }
    for (let i = 0; i < lines.length; i += 1) {
      if (/ResponseEntity\b/.test(lines[i])) {
        error(r, i + 1, 'JAVA-DTO-004', 'Controller 返回 R<T>，禁止使用 ResponseEntity');
      }
      if (/\bcatch\s*\(/.test(lines[i])) {
        error(r, i + 1, 'JAVA-ERR-003', 'Controller 不得捕获异常，交由 GlobalExceptionHandler');
      }
    }
    // LAYER-002：Controller 的 import 白名单
    for (let i = 0; i < lines.length; i += 1) {
      const m = /^import\s+(static\s+)?([\w.]+);/.exec(lines[i].trim());
      if (!m) continue;
      const target = m[2];
      const allowed = CONTROLLER_IMPORT_ALLOW.some((p) => target.startsWith(p))
        || target.startsWith(`com.workbuddy.${module.replace('workbuddy-', '')}.service`);
      if (!allowed) error(r, i + 1, 'JAVA-LAYER-002', `Controller 不得 import：${target}`);
    }
  }

  // TX-001：@Transactional 只标在 application service 的 public 方法
  for (let i = 0; i < lines.length; i += 1) {
    if (!/@Transactional\b/.test(lines[i])) continue;
    if (/^\s*(public\s+)?(final\s+)?(abstract\s+)?(class|interface)\s/.test(lines[i + 1] ?? '')) {
      error(r, i + 1, 'JAVA-TX-001', '@Transactional 不得标在类上');
      continue;
    }
    if (!name.endsWith('ServiceImpl')) {
      error(r, i + 1, 'JAVA-TX-001', '@Transactional 只允许出现在 ServiceImpl 的方法上');
      continue;
    }
    const next = lines[i + 1] ?? '';
    if (!/\bpublic\b/.test(next)) error(r, i + 1, 'JAVA-TX-001', '@Transactional 只允许标在 public 方法上');
  }
}

const args = process.argv.slice(2);
const files = args.length > 0 ? args.map((p) => resolve(ROOT, p)) : allJavaFiles();
for (const f of files) if (existsSync(f)) checkFile(f);

const errors = HITS.filter((h) => h.level === 'ERROR');
const warns = HITS.filter((h) => h.level === 'WARN');

if (HITS.length === 0) {
  console.log(`Java 形态检查：通过（${files.length} 个文件）`);
  process.exit(0);
}

for (const h of HITS) {
  console.log(`${h.level} ${h.file}:${h.line} [${h.rule}] ${h.msg}`);
}
console.log('');
console.log(`Java 形态检查：扫描 ${files.length} 个文件，ERROR ${errors.length}、WARN ${warns.length}`);
process.exit(errors.length > 0 ? 1 : 0);
