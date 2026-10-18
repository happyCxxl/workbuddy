// 命名词表校验：接口层导出的函数必须以约定的动词开头。配置见 config.json 的 vue.verbs。
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';
import { loadConfig } from '../lib.mjs';

const { apiDir: apiRel, verbs } = loadConfig().vue;
const apiDir = join(process.cwd(), apiRel);
if (!existsSync(apiDir)) {
  console.log(`check-naming: ${apiRel} 不存在，跳过`);
  process.exit(0);
}

const verbPattern = new RegExp(`^(${verbs.join('|')})`);

const errors = [];
for (const file of readdirSync(apiDir)) {
  if (!/\.(js|ts)$/.test(file)) {
    continue;
  }
  const content = readFileSync(join(apiDir, file), 'utf8');
  const functionNames = [
    ...[...content.matchAll(/export\s+(?:async\s+)?function\s+(\w+)/g)].map((m) => m[1]),
    ...[...content.matchAll(/export\s+const\s+(\w+)\s*=\s*(?:async\s*)?\(/g)].map((m) => m[1]),
  ];
  for (const name of functionNames) {
    if (!verbPattern.test(name)) {
      errors.push(`${file}: 接口函数 ${name} 必须以 ${verbs.join('/')} 开头（见规范“命名”）`);
    }
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log('check-naming: 通过');
