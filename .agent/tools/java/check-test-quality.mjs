#!/usr/bin/env node
/**
 * 用例质量门禁：拦「为指标写的测试」，不拦「写得不好的测试」。
 *
 * 对应规则：COMMON-TEST-11 至 14、JAVA-TEST-011、COMMON-WORK-12。
 * 四类判定：
 *   1. 恒真断言——断言不可能失败，等于没测；
 *   2. 无断言——只跑代码不检查结果；
 *   3. 只有交互验证——verify 之外没有结果断言；
 *   4. 缺来源说明——用例没写清守护哪条规则或哪个错误码。
 * 另有一项信号：单文件 @Mock 超过 5 个，提示被测类职责过载（WARN，不阻断）。
 *
 * 扫描范围：`src/test/**` 下的 .java；传文件参数时只查这些文件（推送门禁按改动范围调用）。
 *
 * 退出码：有 ERROR → 1；只有 WARN 或全干净 → 0。
 */
import { existsSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import process from 'node:process';
import { ROOT, trackedFiles } from '../lib.mjs';

/** 来源标记：规则编号、错误码，或指向被守护契约的 {@link} / {@code 标识符} / 反引号符号 */
const RULE_REF = /[A-Z]{2,}-[A-Z]{2,}-\d{3}|\b(?:4\d{4}|5\d{4})\b|\{@link\s+[^}]+}|\{@code\s+[\w.#]+}|`[\w.#]+`/;

/** 恒真断言形态 */
const TAUTOLOGY = [
  { pattern: /assertTrue\s*\(\s*true\s*\)/, kind: 'assertTrue(true)' },
  { pattern: /assertFalse\s*\(\s*false\s*\)/, kind: 'assertFalse(false)' },
  { pattern: /assertEquals\s*\(\s*(\d+)\s*,\s*\1\s*\)/, kind: 'assertEquals 同一字面量' },
  { pattern: /assertNull\s*\(\s*null\s*\)/, kind: 'assertNull(null)' },
];

const MOCK_LIMIT = 5;

const HITS = [];
const WARNS = [];

/**
 * 记录一条错误。
 * @param {string} file 相对路径
 * @param {number} line 行号
 * @param {string} rule 规则号
 * @param {string} msg 说明
 */
function error(file, line, rule, msg) {
  HITS.push({ file, line, rule, msg });
}

/**
 * 去掉块注释与行注释，保留行号对齐。
 * @param {string} text 源码
 * @returns {string[]} 按行拆分的净化结果
 */
function stripComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .split(/\r?\n/)
    .map((line) => line.replace(/\/\/.*$/, ''));
}

/**
 * 从 @Test 行起，找到方法体结束行（花括号配平）。
 * @param {string[]} code 净化后的源码行
 * @param {number} start @Test 所在下标
 * @returns {{bodyStart: number, bodyEnd: number}} 方法体范围（含端点）
 */
function methodBlock(code, start) {
  let bodyStart = start;
  while (bodyStart < code.length && !code[bodyStart].includes('{')) bodyStart += 1;
  let depth = 0;
  let bodyEnd = bodyStart;
  for (let i = bodyStart; i < code.length; i += 1) {
    for (const ch of code[i]) {
      if (ch === '{') depth += 1;
      if (ch === '}') depth -= 1;
    }
    bodyEnd = i;
    if (depth === 0) break;
  }
  return { bodyStart, bodyEnd };
}

/**
 * 取方法前置 Javadoc 文本。
 * @param {string[]} lines 原始源码行
 * @param {number} index @Test 所在下标
 * @returns {string} Javadoc 文本；没有则为空串
 */
function precedingJavadoc(lines, index) {
  let cursor = index - 1;
  while (cursor >= 0 && lines[cursor].trim() === '') cursor -= 1;
  if (cursor < 0 || !lines[cursor].trim().endsWith('*/')) return '';
  const collected = [];
  while (cursor >= 0) {
    collected.push(lines[cursor]);
    if (lines[cursor].trim().startsWith('/**')) break;
    cursor -= 1;
  }
  return collected.reverse().join('\n');
}

/**
 * 检查一个测试文件。
 * @param {string} rel 相对路径
 */
function checkFile(rel) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) return;
  const text = readFileSync(abs, 'utf8');
  const lines = text.split(/\r?\n/);
  const code = stripComments(text);

  const mockCount = (text.match(/^\s*@Mock\b/gm) ?? []).length;
  if (mockCount > MOCK_LIMIT) {
    WARNS.push({
      file: rel,
      line: 1,
      rule: 'TESTQ-005',
      msg: `@Mock ${mockCount} 个，超过 ${MOCK_LIMIT} 个：被测类职责可能过载，或替身越过了系统边界`,
    });
  }

  code.forEach((line, i) => {
    for (const { pattern, kind } of TAUTOLOGY) {
      if (pattern.test(line)) {
        error(rel, i + 1, 'TESTQ-001', `恒真断言（${kind}）：断言无法失败，等于没测`);
      }
    }
  });

  for (let i = 0; i < code.length; i += 1) {
    if (!/^\s*@Test\b/.test(code[i]) && !/@Test\b/.test(code[i])) continue;
    const { bodyStart, bodyEnd } = methodBlock(code, i);
    if (bodyStart >= code.length) continue;
    const body = code.slice(bodyStart, bodyEnd + 1).join('\n');
    const hasAssert = /\bassert[A-Z]\w*\s*\(|\bfail\s*\(|\bandExpect\s*\(|\bassertThat\s*\(|\bverifyNoInteractions\s*\(|\bverifyNoMoreInteractions\s*\(/.test(body);
    const hasVerify = /\bverify\s*\(/.test(body);

    if (!hasAssert && !hasVerify) {
      error(rel, bodyStart + 1, 'TESTQ-002', '用例没有任何断言，不检查结果');
    } else if (!hasAssert && hasVerify) {
      WARNS.push({
        file: rel,
        line: bodyStart + 1,
        rule: 'TESTQ-003',
        msg: '只有交互验证（verify），没有结果断言：验证的是调用顺序，不是行为',
      });
    }

    const doc = precedingJavadoc(lines, i);
    if (!RULE_REF.test(doc)) {
      error(rel, i + 1, 'TESTQ-004', '用例说明未写明守护的规则编号或错误码（COMMON-TEST-11）');
    }
  }
}

const args = process.argv.slice(2);
const targets = args.length > 0
  ? args.map((a) => a.split('\\').join('/'))
  : trackedFiles().filter((f) => f.includes('/src/test/') && f.endsWith('.java'));

for (const file of targets) checkFile(file);

const label = args.length > 0 ? `${targets.length} 个文件` : `全部测试文件 ${targets.length} 个`;
if (HITS.length === 0 && WARNS.length === 0) {
  console.log(`用例质量检查：通过（${label}）`);
  process.exit(0);
}

if (HITS.length > 0) {
  console.log(`ERROR ${HITS.length} 处：`);
  for (const hit of HITS) {
    console.log(`  ERROR ${hit.file}:${hit.line} [${hit.rule}] ${hit.msg}`);
  }
}
if (WARNS.length > 0) {
  console.log(`\nWARN ${WARNS.length} 处（信号，不阻断）：`);
  for (const warn of WARNS.slice(0, 20)) {
    console.log(`  WARN ${warn.file}:${warn.line} [${warn.rule}] ${warn.msg}`);
  }
  if (WARNS.length > 20) console.log(`  ... 其余 ${WARNS.length - 20} 处略`);
}
console.log(`\n用例质量检查：扫描 ${relative(ROOT, ROOT) || '.'}，ERROR ${HITS.length}、WARN ${WARNS.length}`);
process.exit(HITS.length === 0 ? 0 : 1);
