#!/usr/bin/env node
// Javadoc 检查：覆盖、形态、标签完整性、行尾注释、注释掉的代码、TODO 格式。
// 判定全部基于结构与正则，不依赖词表，不设豁免。
// 用法：
//   node .agent/tools/java/check-javadoc.mjs            # 全量
//   node .agent/tools/java/check-javadoc.mjs a.java ...  # 指定文件
import { existsSync, readFileSync } from 'node:fs';
import { basename, join } from 'node:path';
import process from 'node:process';
import { ROOT, trackedFiles } from '../lib.mjs';

const MAX_LINE = 120;
const SUMMARY_BANNED = /^(该方法|本方法|这个方法|This method|This function)/;
const TODO_OK = /(TODO|FIXME)\([^)]+\)[:：]\s*\S/;
const TYPE_DECL = /^\s*(?:public|protected)\s+(?:(?:final|abstract|sealed|strictfp|static)\s+)*(class|interface|enum|record)\s+(\w+)/;
const METHOD_DECL = /^\s*(?:public|protected)\s+(?:(?:static|final|abstract|synchronized|native|default|strictfp)\s+)*(?:<[^>]+>\s*)?([\w.$\[\]<>, ?]+?)\s+(\w+)\s*\(/;
const CTOR_DECL = /^\s*(?:public|protected)\s+(\w+)\s*\(/;

const errors = [];
const push = (file, line, rule, msg) => errors.push({ file, line, rule, msg });

/** 从声明行向上找 Javadoc 结束行（跳过注解与空行） */
function javadocEndBefore(lines, declIdx) {
  let k = declIdx - 1;
  while (k >= 0) {
    const t = lines[k].trim();
    if (t === '' || t.startsWith('@')) {
      k -= 1;
      continue;
    }
    return t.endsWith('*/') ? k : -1;
  }
  return -1;
}

/** 取块：返回 {start, end, inner:[{line,text}]} */
function readBlock(lines, endIdx) {
  if (lines[endIdx].trim().startsWith('/**') && lines[endIdx].trim().endsWith('*/')) {
    const text = lines[endIdx].trim().replace(/^\/\*\*/, '').replace(/\*\/$/, '').trim();
    return { start: endIdx, end: endIdx, single: true, inner: [{ line: endIdx, text }] };
  }
  let start = endIdx;
  while (start >= 0 && !lines[start].trim().startsWith('/**')) start -= 1;
  if (start < 0) return null;
  const inner = [];
  for (let i = start + 1; i < endIdx; i += 1) {
    inner.push({ line: i, text: lines[i].replace(/^\s*\* ?/, '') });
  }
  return { start, end: endIdx, single: false, inner };
}

function splitParams(text) {
  const parts = [];
  let depth = 0;
  let cur = '';
  for (const ch of text) {
    if ('<(['.includes(ch)) depth += 1;
    if ('>)]'.includes(ch)) depth -= 1;
    if (ch === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((p) => p.trim()).filter(Boolean);
}

function paramName(segment) {
  const cleaned = segment.replace(/@\w+(\([^)]*\))?/g, ' ').trim();
  const m = /([A-Za-z_$][\w$]*)\s*$/.exec(cleaned);
  return m ? m[1] : null;
}

function joinSignature(lines, idx) {
  let text = lines[idx];
  let depth = (text.match(/\(/g) ?? []).length - (text.match(/\)/g) ?? []).length;
  let i = idx;
  while (depth > 0 && i + 1 < lines.length) {
    i += 1;
    text += ` ${lines[i].trim()}`;
    depth += (lines[i].match(/\(/g) ?? []).length - (lines[i].match(/\)/g) ?? []).length;
  }
  if (depth !== 0) return { text, end: i, broken: true };
  return { text, end: i, broken: false };
}

function checkJavadoc(file, lines, declLine, block, kind, sig) {
  if (!block) {
    push(file, declLine + 1, 'JAVA-DOC-001', `${kind}缺少 Javadoc`);
    return;
  }

  // 行宽
  for (let i = block.start; i <= block.end; i += 1) {
    if (lines[i].length > MAX_LINE) push(file, i + 1, 'JAVA-DOC-006', `注释行超过 ${MAX_LINE} 字符`);
  }

  if (block.single) {
    const hasTag = /(^|\s)@(param|return|throws|exception|deprecated|see|since|author|version|serial)\b/
      .test(block.inner[0].text);
    if (hasTag) push(file, block.start + 1, 'JAVA-DOC-006', '含块标签的 Javadoc 不得写成单行');
  } else {
    if (lines[block.start].trim() !== '/**') {
      push(file, block.start + 1, 'JAVA-DOC-006', '多行 Javadoc 的 /** 必须独占一行');
    }
    if (lines[block.end].trim() !== '*/') {
      push(file, block.end + 1, 'JAVA-DOC-006', '多行 Javadoc 的 */ 必须独占一行');
    }
    for (let i = block.start + 1; i < block.end; i += 1) {
      if (!/^\s*\*/.test(lines[i])) push(file, i + 1, 'JAVA-DOC-006', '多行 Javadoc 内容行必须以 * 对齐');
    }
  }

  // 摘要：取第一段文字，句末标点可以落在段内任意一行（摘要允许多行）
  const para = [];
  for (const x of block.inner) {
    const tx = x.text.trim();
    if (tx.startsWith('@')) break;
    if (tx === '') {
      if (para.length > 0) break;
      continue;
    }
    para.push({ line: x.line, text: tx });
  }
  if (para.length === 0) {
    push(file, block.start + 1, 'JAVA-DOC-006', 'Javadoc 缺少摘要');
  } else {
    if (SUMMARY_BANNED.test(para[0].text)) {
      push(file, para[0].line + 1, 'JAVA-DOC-006', '摘要不得以「该方法/本方法」开头');
    }
    const last = para[para.length - 1];
    if (!/[。.!！?？]$/.test(last.text)) {
      push(file, last.line + 1, 'JAVA-DOC-006', '摘要必须以句号结尾');
    }
  }

  // 标签
  const tags = block.inner
    .map((x, i) => ({ ...x, order: i }))
    .filter((x) => x.text.trim().startsWith('@'))
    .map((x) => {
      const m = /@(\w+)\s*(.*)$/.exec(x.text.trim());
      return m ? { tag: m[1], rest: m[2], line: x.line } : null;
    })
    .filter(Boolean);

  const ORDER = ['param', 'return', 'throws', 'deprecated'];
  let last = -1;
  for (const t of tags) {
    const idx = ORDER.indexOf(t.tag);
    if (idx < 0) continue;
    if (idx < last) push(file, t.line + 1, 'JAVA-DOC-006', `块标签顺序错误：@${t.tag}`);
    last = Math.max(last, idx);
    if (!t.rest.trim()) push(file, t.line + 1, 'JAVA-DOC-006', `@${t.tag} 不得为空`);
  }

  const docParams = new Set(tags.filter((t) => t.tag === 'param').map((t) => t.rest.split(/\s+/)[0]));
  const docThrows = new Set(tags.filter((t) => t.tag === 'throws').map((t) => t.rest.split(/\s+/)[0]));

  const params = sig.params.map(paramName).filter(Boolean);
  for (const p of params) {
    if (!docParams.has(p)) push(file, declLine + 1, 'JAVA-DOC-002', `@param 缺少参数 ${p}`);
  }
  for (const p of docParams) {
    // 类型参数（@param <T>）不参与形参比对
    if (/^<.+>$/.test(p)) continue;
    if (!params.includes(p)) push(file, declLine + 1, 'JAVA-DOC-006', `@param ${p} 不在方法签名中`);
  }

  const isVoid = kind === '构造器' || sig.returnType === 'void';
  const hasReturn = tags.some((t) => t.tag === 'return');
  if (!isVoid && !hasReturn) push(file, declLine + 1, 'JAVA-DOC-002', '非 void 方法缺少 @return');
  if (isVoid && hasReturn) push(file, declLine + 1, 'JAVA-DOC-006', 'void 方法不应有 @return');

  for (const e of sig.throws) {
    if (!docThrows.has(e)) push(file, declLine + 1, 'JAVA-DOC-002', `@throws 缺少异常 ${e}`);
  }
}

function checkFile(file) {
  const abs = join(ROOT, file);
  if (!existsSync(abs)) return;
  const name = basename(file, '.java');
  if (name === 'package-info' || name === 'module-info') return;
  if (!file.includes('/src/main/java/')) return;

  const lines = readFileSync(abs, 'utf8').split(/\r?\n/);

  // 先标出所有 Javadoc 块（含单行），块内行不参与声明解析，但仍参与逐行检查
  const inBlock = new Array(lines.length).fill(false);
  for (let i = 0; i < lines.length; i += 1) {
    if (!lines[i].trim().startsWith('/**')) continue;
    if (lines[i].trim().endsWith('*/')) {
      inBlock[i] = true;
      continue;
    }
    let k = i + 1;
    while (k < lines.length && !lines[k].trim().endsWith('*/')) k += 1;
    for (let j = i; j <= Math.min(k, lines.length - 1); j += 1) inBlock[j] = true;
    i = k;
  }

  for (let i = 0; i < lines.length; i += 1) {
    const t = lines[i].trim();

    if (t.includes('@author') || t.includes('@since')) {
      push(file, i + 1, 'JAVA-DOC-004', '不得写作者与日期');
    }

    // 注释掉的代码
    if (t.startsWith('//')) {
      const body = t.slice(2).trim();
      const looksLikeCode =
        /[;{}]$/.test(body) ||
        /^(return|if|for|while|switch|new|import|package|System\.|this\.|super\.)\b/.test(body) ||
        /^[A-Za-z_$][\w.$]*\s*[=(]/.test(body);
      const looksLikeProse = /[。，、；：]$/.test(body) || /[\u4e00-\u9fa5]/.test(body);
      if (looksLikeCode && !looksLikeProse && !/^(TODO|FIXME)/.test(body)) {
        push(file, i + 1, 'JAVA-DOC-007', '不得保留注释掉的代码');
      }
    }

    // 行尾注释
    if (!t.startsWith('//') && !t.startsWith('*') && !t.startsWith('/*')) {
      const idx = lines[i].indexOf('//');
      if (idx > 0 && lines[i].slice(0, idx).trim() !== '') {
        const before = lines[i].slice(0, idx);
        const isUrl = /:\s*$/.test(before) || /https?:$/.test(before);
        if (!isUrl) push(file, i + 1, 'JAVA-DOC-007', '禁止行尾注释，注释应独立成行');
      }
    }

    // TODO 格式
    if (/\b(TODO|FIXME)\b/.test(t) && !TODO_OK.test(t)) {
      push(file, i + 1, 'JAVA-DOC-008', 'TODO/FIXME 必须写成 TODO(负责人): 触发条件');
    }

    if (inBlock[i]) continue;

    const typeMatch = TYPE_DECL.exec(lines[i]);
    if (typeMatch) {
      const block = javadocEndBefore(lines, i);
      // record 的组件写在头部括号里，按形参对待（@param 逐个对应）
      let recordParams = [];
      if (typeMatch[1] === 'record') {
        const header = joinSignature(lines, i).text;
        const open = header.indexOf('(');
        const close = header.lastIndexOf(')');
        if (open >= 0 && close > open) recordParams = splitParams(header.slice(open + 1, close));
      }
      checkJavadoc(file, lines, i, block >= 0 ? readBlock(lines, block) : null, typeMatch[1], {
        params: recordParams,
        returnType: 'void',
        throws: [],
      });
      if (typeMatch[1] === 'enum') {
        for (let k = i + 1; k < lines.length; k += 1) {
          const ct = lines[k].trim();
          if (ct.startsWith('}')) break;
          if (!/^[A-Z][A-Z0-9_]*\s*(\(|,|;|$)/.test(ct)) continue;
          let p = k - 1;
          while (p >= 0 && lines[p].trim() === '') p -= 1;
          const prev = p >= 0 ? lines[p].trim() : '';
          if (!prev.startsWith('/**') && !prev.startsWith('//') && !prev.startsWith('*') && !prev.endsWith('{')) {
            push(file, k + 1, 'JAVA-DOC-002', `枚举常量 ${ct.split(/[(,;]/)[0]} 缺少注释`);
          }
        }
      }
      continue;
    }

    const isCtor = CTOR_DECL.exec(lines[i]) && CTOR_DECL.exec(lines[i])[1] === name;
    const isMethod = METHOD_DECL.exec(lines[i]) && !isCtor;
    if (!isMethod && !isCtor) continue;

    const joined = joinSignature(lines, i);
    if (joined.broken) continue;
    const sigText = joined.text;
    const open = sigText.indexOf('(');
    const close = sigText.lastIndexOf(')');
    if (open < 0 || close < open) continue;
    const params = splitParams(sigText.slice(open + 1, close));
    const throwsPart = /throws\s+([\w.,\s]+?)(?:\{|$)/.exec(sigText.slice(close));
    const throwsList = throwsPart ? throwsPart[1].split(',').map((x) => x.trim()).filter(Boolean) : [];

    let returnType = 'void';
    if (isMethod) {
      const m = METHOD_DECL.exec(lines[i]);
      returnType = m[1].trim();
    }

    const block = javadocEndBefore(lines, i);
    checkJavadoc(file, lines, i, block >= 0 ? readBlock(lines, block) : null, isCtor ? '构造器' : '方法', {
      params,
      returnType,
      throws: throwsList,
    });
    i = joined.end;
  }
}

const args = process.argv.slice(2);
const files = args.length > 0 ? args.map((p) => p.replace(/\\/g, '/')) : trackedFiles().filter((f) => f.endsWith('.java'));

for (const f of files) checkFile(f);

if (errors.length === 0) {
  console.log(`Javadoc 检查：通过（${files.length} 个文件）`);
  process.exit(0);
}
errors.sort((a, b) => (a.file === b.file ? a.line - b.line : a.file.localeCompare(b.file)));
for (const e of errors) console.log(`ERROR ${e.file}:${e.line} [${e.rule}] ${e.msg}`);
const byRule = new Map();
for (const e of errors) byRule.set(e.rule, (byRule.get(e.rule) ?? 0) + 1);
console.log('');
console.log(`Javadoc 检查：扫描 ${files.length} 个文件，ERROR ${errors.length}`);
for (const [rule, count] of [...byRule.entries()].sort((a, b) => b[1] - a[1])) {
  console.log(`  ${rule}: ${count}`);
}
process.exit(1);
