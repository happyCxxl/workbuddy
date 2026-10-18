// 命名词表校验（§3.2）：src/api/ 下导出的接口函数必须以 add/update/delete/get/detail 开头。
import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import process from 'node:process';

const apiDir = join(process.cwd(), 'src', 'api');
if (!existsSync(apiDir)) {
  console.log('check-naming: src/api 不存在，跳过');
  process.exit(0);
}

const VERBS = ['add', 'update', 'delete', 'get', 'detail'];
const verbPattern = new RegExp(`^(${VERBS.join('|')})`);

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
      errors.push(
        `${file}: 接口函数 ${name} 必须以 add/update/delete/get/detail 开头（见规范 §3.2）`,
      );
    }
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log('check-naming: 通过');
