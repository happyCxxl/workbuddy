// 列表表格口径校验（VUE-CSS-003/004/005）：表格挂全局骨架类；同一张表各列同 min-width、不写 width、统一居中。
// 列宽由 Element Plus 按各列 min-width 比例分配富余宽度，这几条只能靠列的写法保证，样式层管不到。
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { lineOf, loadConfig, walkVueFiles } from '../lib.mjs';

const cfg = loadConfig()['vue'];
const srcDir = join(process.cwd(), cfg.srcDir);
const errors = [];
const TABLE_CLASS = new RegExp(`class="[^"]*\\b${cfg.tableClass}\\b[^"]*"`);
const COLUMN_TAG = cfg.columnTag;
const COLUMN_MIN_WIDTH = /(?:^|\s):?min-width="(\d+)"/;
const COLUMN_WIDTH = /(?:^|\s):?width="/;
const COLUMN_ALIGN = /(?:^|\s)align="center"/;

/**
 * 校验一张表：表块取 `<el-table …>` 到最近的 `</el-table>`，
 * 列取块内的 `<el-table-column …>` 开标签。
 */
function checkTable(rel, content, blockStart, block) {
  const tagEnd = block.indexOf('>');
  const tableTag = block.slice(0, tagEnd === -1 ? block.length : tagEnd + 1);
  if (!TABLE_CLASS.test(tableTag)) {
    errors.push(
      `${rel}:${lineOf(content, blockStart)}: 表格必须挂 class="${cfg.tableClass}"（VUE-CSS-003）`,
    );
  }

  const widths = [];
  for (const match of block.matchAll(new RegExp(`<${COLUMN_TAG}[\\s>][^>]*>`, 'g'))) {
    const tag = match[0];
    const index = blockStart + match.index;
    const line = lineOf(content, index);
    if (COLUMN_WIDTH.test(tag)) {
      errors.push(
        `${rel}:${line}: 列不得写 width（该列退出富余宽度分配，等宽被打破）（VUE-CSS-004）`,
      );
      continue;
    }
    if (!COLUMN_ALIGN.test(tag)) {
      errors.push(`${rel}:${line}: 列必须写 align="center"（表头跟随单元格居中）（VUE-CSS-005）`);
    }
    const minWidth = COLUMN_MIN_WIDTH.exec(tag);
    if (!minWidth) {
      errors.push(`${rel}:${line}: 列必须写 min-width（数值字面量，VUE-CSS-004）`);
      continue;
    }
    widths.push(minWidth[1]);
  }

  const distinct = [...new Set(widths)];
  if (distinct.length > 1) {
    errors.push(
      `${rel}:${lineOf(content, blockStart)}: 同一张表各列 min-width 必须相同，` +
        `当前为 ${distinct.join(' / ')}（VUE-CSS-004）`,
    );
  }
}

for (const file of walkVueFiles(srcDir)) {
  const rel = file.slice(process.cwd().length + 1).replace(/\\/g, '/');
  const content = readFileSync(file, 'utf8');
  let cursor = 0;
  const openTag = `<${cfg.tableTag}`;
  const closeTag = `</${cfg.tableTag}>`;
  while (cursor < content.length) {
    const open = content.indexOf(openTag, cursor);
    if (open === -1) {
      break;
    }
    if (!/^[\s>]/.test(content.slice(open + openTag.length, open + openTag.length + 1))) {
      cursor = open + openTag.length;
      continue;
    }
    const close = content.indexOf(closeTag, open);
    checkTable(rel, content, open, content.slice(open, close === -1 ? content.length : close));
    cursor = close === -1 ? content.length : close + closeTag.length;
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  console.error('');
  console.error('复跑：cd web && pnpm lint:table');
  process.exit(1);
}
console.log('check-table: 通过');
