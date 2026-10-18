#!/bin/sh
# 推送前门禁：对「本次要推上去的改动」跑注释口径 + 文件编码 + AGENTS 入口体检 + 前端全量 lint + 后端源码级/字节码级门禁。
#
# 用法：
#   1) 由 .husky/pre-push 调用（从标准输入读 push 协议行）；
#   2) 手动预跑：sh .agent/tools/pre-push-gate.sh [base..head]
#      不带参数时比对当前分支与它的 upstream（没有 upstream 时用 origin/main）；
#   3) FORCE_GATE=1 强制重跑（忽略指纹缓存）。
#
# 范围：本次推送范围（远端 sha..本地 sha）内变更的文件；新分支或远端基点不可得时退回全量检查。
#
# 指纹缓存：全部步骤通过后把指纹写进 .git/workbuddy-prepush-ok。指纹 = 本地 sha + 远端 sha +
#   改动文件清单 + 本脚本/钩子/注释检查器/pom 的内容哈希。同一次推送重试时命中缓存直接跳过，
#   任何文件改动（含钩子、检查器、pom）都会让指纹失效并全量重跑。
#
# 输出口径：先打印本次要跑几步、涉及多少改动文件；每步前打 [i/N] 横幅，子命令输出原样透传；
#   每步后打通过与否 + 耗时；失败时指出第几步并给复出手动命令；最后给总耗时。
#   标记一律 ASCII（==== / ---- / !!!!），避免不同终端编码下中文与符号错位。
set -e

root=$(git rev-parse --show-toplevel)
cache_file=".git/workbuddy-prepush-ok"
zero=0000000000000000000000000000000000000000

# ---------- 1. 取推送范围 ----------
ranges=""
full=0
read_lines=0
if [ -n "${1:-}" ]; then
  # 手动指定范围（优先级最高）：sh .agent/tools/pre-push-gate.sh origin/main..HEAD
  ranges="$1"
else
  # 钩子触发时从标准输入读 push 协议行（<local ref> <local sha> <remote ref> <remote sha>）；
  # 手动执行（没有协议行，可能也没有终端，例如在后台/重定向下跑）时改按 upstream 兜底。
  while read -r local_ref local_sha remote_ref remote_sha; do
    read_lines=$((read_lines + 1))
    if [ "$local_sha" = "$zero" ]; then
      continue
    fi
    if [ "$remote_sha" = "$zero" ] || ! git cat-file -e "$remote_sha^{commit}" 2>/dev/null; then
      full=1
      break
    fi
    ranges="$ranges $remote_sha..$local_sha"
  done
  if [ "$read_lines" = "0" ] && [ "$full" = "0" ]; then
    base=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || echo origin/main)
    if git cat-file -e "$base^{commit}" 2>/dev/null; then
      ranges="$(git rev-parse "$base")..$(git rev-parse HEAD)"
    else
      full=1
    fi
  fi
fi

ranges=$(printf '%s' "$ranges" | sed 's/^ *//')

if [ "$full" = "1" ] || [ -z "$ranges" ]; then
  changed=$(git -c core.quotePath=false ls-tree -r --name-only HEAD)
  scope_note="远端基点不可得，按全量检查"
else
  changed=$(git -c core.quotePath=false diff --name-only $ranges | sort -u)
  scope_note="本次推送范围 $ranges"
fi

changed_count=$(printf '%s\n' "$changed" | sed '/^$/d' | wc -l | tr -d ' ')

# ---------- 2. 指纹缓存 ----------
changed_hash=$(printf '%s' "$changed" | git hash-object --stdin)
hook_hash=$(git hash-object .husky/pre-push 2>/dev/null || echo nohook)
script_hash=$(git hash-object .agent/tools/pre-push-gate.sh 2>/dev/null || echo noscript)
checker_hash=$(git hash-object .agent/tools/check-comments.mjs 2>/dev/null || echo nochecker)
agents_hash=$(git hash-object .agent/tools/check-agents.mjs 2>/dev/null || echo noagents)
pom_hash=$(git hash-object pom.xml 2>/dev/null || echo nopom)
fingerprint=$(printf '%s\n%s\n%s\n%s\n%s\n%s\n%s' \
  "$ranges" "$changed_hash" "$hook_hash" "$script_hash" "$checker_hash" "$agents_hash" "$pom_hash" | git hash-object --stdin)

# 排查用：GATE_DEBUG=1 打印指纹组成（缓存命中/未命中时都能看出是哪一项变了）
if [ "${GATE_DEBUG:-0}" = "1" ]; then
  echo "指纹组成：ranges=$ranges"
  echo "          changed=$changed_hash（$changed_count 个文件）"
  echo "          hook=$hook_hash script=$script_hash checker=$checker_hash agents=$agents_hash pom=$pom_hash"
  echo "          合计=$fingerprint"
fi

if [ "${FORCE_GATE:-0}" != "1" ] && [ -f "$cache_file" ]; then
  # 比对前统一去掉行尾 CR/空白：缓存可能被别的工具按不同行尾重写过，比对不该因 \r 失败
  cached=$(sed -n '1p' "$cache_file" | tr -d ' \r\n')
  cached_at=$(sed -n '2p' "$cache_file" | tr -d '\r\n')
  if [ "$cached" = "$fingerprint" ]; then
    echo ""
    echo "==================== 推送前检查 ===================="
    echo "范围：$scope_note（$changed_count 个改动文件）"
    echo "结论：与上次通过时完全一致，跳过（上次通过：$cached_at）"
    echo "      指纹 ${fingerprint%${fingerprint#???????}}…；强制重跑用 FORCE_GATE=1"
    echo "===================================================="
    exit 0
  fi
  # 未命中时给出可比对的证据，避免"又重跑了"只能靠猜：旧指纹与新指纹各打一条，细节看 GATE_DEBUG
  echo ""
  echo "---- 缓存未命中：重跑（旧 ${cached:-无} → 新 $fingerprint）"
  echo "     查是哪一项变了：GATE_DEBUG=1 sh .agent/tools/pre-push-gate.sh"
fi

# ---------- 3. 规划步骤 ----------
run_comments=0
run_encoding=0
run_agents=0
run_frontend=0
run_source=0
run_conventions=0
run_bytecode=0
run_tests=0
run_coverage=0
run_modules=0
if printf '%s\n' "$changed" | grep -qE '(\.java$|\.ts$|\.vue$|\.css$|\.mjs$|\.md$|^\.husky/|^\.agent/tools/check-comments\.mjs$)'; then
  run_comments=1
fi
if printf '%s\n' "$changed" | grep -qE '(\.java$|\.ts$|\.vue$|\.css$|\.mjs$|\.js$|\.md$|\.sql$|\.ya?ml$|\.json$|\.html$|\.sh$|\.xml$|\.properties$|^\.agent/tools/|^docker/|^\.agent/)'; then
  run_encoding=1
fi
if printf '%s\n' "$changed" | grep -qE '(AGENTS\.md$|^\.agent/)'; then
  run_agents=1
fi
if printf '%s\n' "$changed" | grep -qE '^(web|\.agent/tools/frontend)/'; then
  run_frontend=1
fi
if printf '%s\n' "$changed" | grep -qE '(\.java$|pom\.xml$|^\.agent/tools/backend/|01-分层与包结构\.md$)'; then
  run_source=1
  run_conventions=1
  run_bytecode=1
  run_tests=1
  run_coverage=1
  run_modules=1
fi
total=$((run_comments + run_encoding + run_agents + run_frontend + run_source + run_conventions + run_tests + run_coverage + run_bytecode + run_modules))

echo ""
echo "==================== 推送前检查 ===================="
echo "范围：$scope_note（$changed_count 个改动文件）"
if [ "$total" = "0" ]; then
  echo "结论：改动不涉及前后端源码，无需检查"
  echo "===================================================="
  {
  printf '%s\n%s\n' "$fingerprint" "$(date '+%Y-%m-%d %H:%M:%S')"
  printf 'ranges=%s\nchanged=%s(%s 个文件)\nhook=%s\nscript=%s\nchecker=%s\nagents=%s\npom=%s\n' \
    "$ranges" "$changed_hash" "$changed_count" "$hook_hash" "$script_hash" "$checker_hash" "$agents_hash" "$pom_hash"
} > "$cache_file"
  exit 0
fi
echo "步骤：共 $total 步，耗时最长的是后端单元测试与字节码门禁（test / compile + spotbugs）"
echo "===================================================="

step=0
started_all=$(date +%s)

# 注释口径：只写"做什么"（职责与行为），禁词表见 .agent/tools/check-comments.mjs。
if [ "$run_comments" = "1" ]; then
  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 注释口径：node .agent/tools/check-comments.mjs"
  echo "     （阶段词 / 历史沿革 / 因果目的 / 给谁用；ERROR 拦下，WARN 只提示）"
  if node "$root/.agent/tools/check-comments.mjs"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 注释口径检查失败（上方列出 文件:行 与命中的词）"
    echo "     复跑：node .agent/tools/check-comments.mjs"
    echo "     说明：注释只写做什么；确需保留禁词时在该行写「口径豁免：<理由>」"
    exit 1
  fi
fi

# 文件编码：源码/文档是否被系统编码误读误写（PowerShell 写文件常见的破坏形态）。
if [ "$run_encoding" = "1" ]; then
  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 文件编码：node .agent/tools/check-encoding.mjs"
  echo "     （替换字符 U+FFFD 与 GBK 误读序列；命中即拦下）"
  if node "$root/.agent/tools/check-encoding.mjs"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 文件编码检查失败（上方列出 文件:行 与命中形态）"
    echo "     复跑：node .agent/tools/check-encoding.mjs"
    echo "     说明：用 UTF-8 工具重写受影响的行；确需举例写出这些序列时在该行写「编码豁免：<理由>」"
    exit 1
  fi
fi

# AGENTS 入口体检：入口文件引用的路径必须存在；行数预算超限只提示。
if [ "$run_agents" = "1" ]; then
  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] AGENTS 入口体检：node .agent/tools/check-agents.mjs"
  echo "     （指针失效 ERROR 拦下；行数预算超限 WARN 只提示）"
  if node "$root/.agent/tools/check-agents.mjs"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] AGENTS 入口体检失败（上方列出失效指针）"
    echo "     复跑：node .agent/tools/check-agents.mjs"
    echo "     说明：更正入口文件里的路径，或恢复被删/被移动的目标文件"
    exit 1
  fi
fi

# 前端组：全量检查；自动修复在 pre-commit 的 lint-staged。
if [ "$run_frontend" = "1" ]; then
  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 前端全量检查：pnpm lint"
  echo "     （prettier / eslint / vue-tsc / stylelint / ls-lint / 命名 / 规范 / 表格口径，共八项）"
  if (
    cd "$root/web"
    pnpm lint
  ); then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 前端检查失败"
    echo "     复跑：cd web && pnpm lint"
    exit 1
  fi
fi

# 模块依赖一致性：文档 §1 的矩阵与各模块 pom.xml 双向核对，改动涉及 Java、pom 或矩阵文档时执行。
if [ "$run_modules" = "1" ]; then
  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 模块依赖一致性：node .agent/tools/check-modules.mjs"
  if node .agent/tools/check-modules.mjs; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 模块依赖与文档不一致"
    echo "     修复：改 .agent/standards/java/01-分层与包结构.md §1 的模块表，或删掉 pom 里多余的依赖"
    exit 1
  fi
fi

# 后端组：源码级分析（checkstyle / pmd / cpd）与字节码级分析（compile + spotbugs）。
# 改动模块用 -pl <改动模块> -amd 展开「改动模块 + 依赖它的模块」；改到 pom 或 .agent/tools/backend 时全量。
if [ "$run_source" = "1" ]; then
  scoped=$(printf '%s\n' "$changed" | grep -oE '^workbuddy-[a-z-]+' | sort -u | tr '\n' ',' | sed 's/,$//')
  if [ -z "$scoped" ] || printf '%s\n' "$changed" | grep -qE '^(pom\.xml$|\.agent/tools/backend/)'; then
    scope_arg=""
    bytecode_scope="全量 9 模块（改到了 pom 或 .agent/tools/backend，闭包即全量）"
  else
    scope_arg="-pl \"$scoped\" -amd"
    bytecode_scope="改动模块及下游：$scoped"
  fi

  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 后端源码级门禁：checkstyle + pmd + cpd"
  echo "     （范围：${bytecode_scope:-全量 9 模块}）"
  if eval "mvn -o $scope_arg checkstyle:check pmd:check pmd:cpd-check"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 后端源码级门禁失败"
    echo "     复跑：mvn -o checkstyle:check pmd:check pmd:cpd-check"
    echo "     提示：报 cannot find symbol 或 PMD UnusedPrivateMethod 时，先给被依赖模块回写本地仓库："
    echo "           mvn -o -q install -DskipTests   （CLI 调用读的是 .m2 里的 jar）"
    exit 1
  fi

  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] Java 形态检查：check-java-conventions（本次改动的 .java）"
  conventions_files=$(printf '%s\n' "$changed" | grep -E '\.java$' || true)
  if [ -z "$conventions_files" ]; then
    echo "---- [$step/$total] 跳过（本次改动不含 .java）"
  elif node .agent/tools/check-java-conventions.mjs $conventions_files; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] Java 形态不合规"
    echo "     复跑：node .agent/tools/check-java-conventions.mjs <文件...>（不带参数为全量审计）"
    echo "     规则：.agent/standards/java/00-总则与规则索引.md"
    exit 1
  fi

  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 后端单元测试：mvn -o test"
  echo "     （范围：$bytecode_scope；推送前全量兜底，开发期的分档见 .agent/standards/java/06-测试.md §8）"
  if eval "mvn -o $scope_arg test"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 后端单元测试失败"
    echo "     复跑：mvn -o $scope_arg test"
    echo "     说明：失败可能来自「改坏了代码」或「测试已过时」，先看失败用例的断言信息再判断"
    exit 1
  fi

  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 覆盖率：jacoco:report + jacoco:check（全库语句 ≥70%）"
  echo "     （范围：$bytecode_scope）"
  if eval "mvn -o $scope_arg jacoco:report jacoco:check"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 覆盖率未达标"
    echo "     复跑：mvn -o $scope_arg jacoco:report jacoco:check"
    echo "     报告：<模块>/target/site/jacoco/index.html"
    echo "     提示：报「plugin ... has not been downloaded」时，先联网跑一次 mvn verify 缓存插件"
    exit 1
  fi

  step=$((step + 1))
  started=$(date +%s)
  echo ""
  echo ">>>> [$step/$total] 后端字节码门禁：compile + spotbugs"
  echo "     （范围：$bytecode_scope）"
  if eval "mvn -o $scope_arg compile spotbugs:check"; then
    echo "---- [$step/$total] 通过（$(( $(date +%s) - started ))s）"
  else
    echo ""
    echo "!!!! [$step/$total] 后端字节码门禁失败"
    echo "     复跑：mvn -o $scope_arg compile spotbugs:check"
    exit 1
  fi
fi

{
  printf '%s\n%s\n' "$fingerprint" "$(date '+%Y-%m-%d %H:%M:%S')"
  printf 'ranges=%s\nchanged=%s(%s 个文件)\nhook=%s\nscript=%s\nchecker=%s\nagents=%s\npom=%s\n' \
    "$ranges" "$changed_hash" "$changed_count" "$hook_hash" "$script_hash" "$checker_hash" "$agents_hash" "$pom_hash"
} > "$cache_file"

echo ""
echo "==================== 检查通过 ===================="
echo "总耗时 $(( $(date +%s) - started_all ))s，现在开始传输"
echo "（同范围重推会命中指纹缓存，跳过重复检查）"
echo "=================================================="
