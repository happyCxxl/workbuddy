#!/usr/bin/env node
// Java 形态检查：把 Java 侧的形态规则落成可执行判定。
// 通用约定：扫描仓库内受版本控制的 .java 文件，模块名取路径首段，不写死任何项目名称。
// 用法：
//   node .agent/tools/check-java-conventions.mjs             # 全量审计
//   node .agent/tools/check-java-conventions.mjs a.java ...   # 只查指定文件（门禁按改动范围调用）
import { existsSync, readFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { ROOT, trackedFiles } from './lib.mjs';

// 后缀词表：与规范 java/03 的词表保持一致
const SUFFIXES = [
  'Controller', 'Service', 'ServiceImpl', 'DbService', 'DbServiceImpl', 'Mapper',
  'DTO', 'VO', 'Assembler', 'Support', 'Helper', 'Handler', 'Provider', 'Registry',
  'Resolver', 'Runner', 'Config', 'Properties', 'Util', 'Exception', 'Enum',
  'Strategy', 'Context', 'Result', 'Info', 'Processor', 'Record', 'Snapshot',
  'Factory', 'Builder', 'Listener', 'Client', 'Consumer', 'Query', 'Param',
];
const FORBIDDEN_SUFFIX = ['Request', 'Response', 'View', 'Filter'];
const DATA_SUFFIX = /(Mapper|DbService)$/;

const HITS = [];
const error = (file, line, rule, msg) => HITS.push({ level: 'ERROR', file, line, rule, msg });

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
    return line.startsWith('/**') || line.startsWith('/*');
  }
  return false;
}

function checkFile(file) {
  const abs = join(ROOT, file);
  if (!existsSync(abs)) return;
  const text = readFileSync(abs, 'utf8');
  const lines = text.split(/\r?\n/);
  const name = basename(file, '.java');
  if (name === 'package-info' || name === 'module-info') return;

  const inTest = /\/src\/test\//.test(file);

  lines.forEach((line, i) => {
    if (/@author\b/.test(line)) error(file, i + 1, 'JAVA-DOC-004', '注释不得写作者');
    if (/System\.(out|err)\s*\./.test(line)) error(file, i + 1, 'JAVA-LOG-001', '禁止 System.out / System.err');
    if (/\.printStackTrace\s*\(/.test(line)) error(file, i + 1, 'JAVA-LOG-001', '禁止 printStackTrace');
  });

  if (lines.length > 400) error(file, 1, 'JAVA-LAYER-005', `单文件 ${lines.length} 行，超过 400 行上限`);

  if (inTest) {
    if (!name.endsWith('Test')) error(file, classLine(lines) || 1, 'JAVA-TEST-001', '测试类必须以 Test 结尾');
    return;
  }

  const line = classLine(lines);
  if (!hasClassJavadoc(lines)) error(file, line || 1, 'JAVA-DOC-001', '缺少类级注释');

  for (const s of FORBIDDEN_SUFFIX) {
    if (name.endsWith(s)) error(file, line || 1, 'JAVA-NAME-002', `禁止使用 ${s} 后缀：请求用 DTO、响应用 VO`);
  }
  if (/Dto$/.test(name)) error(file, line || 1, 'JAVA-NAME-002', '请求对象后缀必须全大写 DTO');

  const isAbstractBase = /^(Abstract|Base)/.test(name);
  const hasSuffix = SUFFIXES.some((s) => name.endsWith(s));
  if (!isAbstractBase && !hasSuffix) {
    error(file, line || 1, 'JAVA-NAME-001', `类名后缀不在词表内：${name}`);
  }

  if (name.endsWith('ServiceImpl')) {
    const iface = name.replace(/Impl$/, '.java');
    if (!existsSync(resolve(dirname(join(ROOT, file)), '..', iface))) {
      error(file, line || 1, 'JAVA-NAME-003', `缺少同名接口 ${iface}`);
    }
  }

  if (name.endsWith('DTO') && !/[\\/]dto[\\/]request[\\/]/.test(file)) {
    error(file, line || 1, 'JAVA-DTO-001', '请求对象应放在 dto/request 下');
  }
  if (name.endsWith('VO') && !/[\\/]dto[\\/]response[\\/]/.test(file)) {
    error(file, line || 1, 'JAVA-DTO-001', '响应对象应放在 dto/response 下');
  }

  if (name.endsWith('Controller')) {
    lines.forEach((l, i) => {
      if (/ResponseEntity\b/.test(l)) error(file, i + 1, 'JAVA-DTO-004', '入口层返回统一响应外壳，不用 ResponseEntity');
      if (/\bcatch\s*\(/.test(l)) error(file, i + 1, 'JAVA-ERR-003', '入口层不捕获异常');
    });
    for (let i = 0; i < lines.length; i += 1) {
      if (!/@RequestBody\b/.test(lines[i])) continue;
      const window = lines.slice(Math.max(0, i - 3), i + 3).join('\n');
      if (!/@Valid\b/.test(window)) error(file, i + 1, 'JAVA-DTO-002', '@RequestBody 参数缺少校验注解');
    }
    lines.forEach((l, i) => {
      const m = /^\s*import\s+(?:static\s+)?([\w.]+);/.exec(l);
      if (m && DATA_SUFFIX.test(m[1].split('.').pop())) {
        error(file, i + 1, 'JAVA-LAYER-002', `入口层不得直接依赖数据访问：${m[1]}`);
      }
    });
  }

  lines.forEach((l, i) => {
    if (!/@Transactional\b/.test(l)) return;
    const next = lines[i + 1] ?? '';
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
