// 列表表格口径校验（§6.7~§6.9）：表格挂全局骨架类；同一张表各列同 min-width、不写 width、统一居中。
// 列宽由 Element Plus 按各列 min-width 比例分配富余宽度，这几条只能靠列的写法保证，样式层管不到。
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';

const srcDir = join(process.cwd(), 'src');
const errors = [];
/** 逐列豁免标记：写在该列所在行 */
const EXEMPT = '表格豁免';
const TABLE_CLASS = /class="[^"]*\bpage-table\b[^"]*"/;
const COLUMN_MIN_WIDTH = /(?:^|\s):?min-width="(\d+)"/;
const COLUMN_WIDTH = /(?:^|\s):?width="/;
const COLUMN_ALIGN = /(?:^|\s)align="center"/;

function walkVueFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walkVueFiles(full));
    } else if (entry.name.endsWith('.vue')) {
      out.push(full);
    }
  }
  return out;
}

/** 行号：从文件头数到指定下标 */
function lineOf(content, index) {
  return content.slice(0, index).split('\n').length;
}

/**
 * 校验一张表：表块取 `<el-table …>` 到最近的 `</el-table>`，
 * 列取块内的 `<el-table-column …>` 开标签。
 */
function checkTable(rel, content, blockStart, block) {
  const tagEnd = block.indexOf('>');
  const tableTag = block.slice(0, tagEnd === -1 ? block.length : tagEnd + 1);
  if (!TABLE_CLASS.test(tableTag)) {
    errors.push(
      `${rel}:${lineOf(content, blockStart)}: 表格必须挂 class="page-table"（见规范 §6.7）`,
    );
  }

  const widths = [];
  for (const match of block.matchAll(/<el-table-column[\s>][^>]*>/g)) {
    const tag = match[0];
    const index = blockStart + match.index;
    const line = lineOf(content, index);
    const lineText = content.split('\n')[line - 1] ?? '';
    if (lineText.includes(EXEMPT) || tag.includes(EXEMPT)) {
      continue;
    }
    if (COLUMN_WIDTH.test(tag)) {
      errors.push(
        `${rel}:${line}: 列不得写 width（该列退出富余宽度分配，等宽被打破）（见规范 §6.8）`,
      );
      continue;
    }
    if (!COLUMN_ALIGN.test(tag)) {
      errors.push(`${rel}:${line}: 列必须写 align="center"（表头跟随单元格居中）（见规范 §6.9）`);
    }
    const minWidth = COLUMN_MIN_WIDTH.exec(tag);
    if (!minWidth) {
      errors.push(`${rel}:${line}: 列必须写 min-width（数值字面量，见规范 §6.8）`);
      continue;
    }
    widths.push(minWidth[1]);
  }

  const distinct = [...new Set(widths)];
  if (distinct.length > 1) {
    errors.push(
      `${rel}:${lineOf(content, blockStart)}: 同一张表各列 min-width 必须相同，` +
        `当前为 ${distinct.join(' / ')}（见规范 §6.8）`,
    );
  }
}

for (const file of walkVueFiles(srcDir)) {
  const rel = file.slice(process.cwd().length + 1).replace(/\\/g, '/');
  const content = readFileSync(file, 'utf8');
  let cursor = 0;
  while (cursor < content.length) {
    const open = content.indexOf('<el-table', cursor);
    if (open === -1) {
      break;
    }
    // 只有 el-table 自身：跳过 <el-table-column 这类同前缀标签
    if (!/^[\s>]/.test(content.slice(open + '<el-table'.length, open + '<el-table'.length + 1))) {
      cursor = open + '<el-table'.length;
      continue;
    }
    const close = content.indexOf('</el-table>', open);
    checkTable(rel, content, open, content.slice(open, close === -1 ? content.length : close));
    cursor = close === -1 ? content.length : close + '</el-table>'.length;
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log('check-table: 通过');
