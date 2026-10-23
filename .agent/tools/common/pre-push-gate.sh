#!/bin/sh
# 推送前门禁：按改动范围运行仓库内的检查器；技术栈按仓库实际存在的构建文件决定。
# 通用约定：Maven 仓库跑 mvn，含 lint 脚本的前端仓库跑包管理器 lint（前端目录按改动路径推导）；不写死任何项目路径。
#
# 用法：
#   1) 由 git 钩子调用（从标准输入读 push 协议行）；
#   2) 手动预跑：sh .agent/tools/common/pre-push-gate.sh [base..head]
#   3) FORCE_GATE=1 强制重跑
set -u

root=$(git rev-parse --show-toplevel 2>/dev/null || pwd)

range_arg="${1:-}"
if [ -n "$range_arg" ]; then
  range="$range_arg"
else
  upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}' 2>/dev/null || true)
  if [ -z "$upstream" ]; then
    upstream='origin/main'
  fi
  range="$upstream...HEAD"
fi

changed=$(git diff --name-only "$range" 2>/dev/null || git diff --name-only HEAD~1 2>/dev/null || true)
if [ -z "$changed" ]; then
  echo "推送前门禁：本次没有改动，跳过"
  exit 0
fi

step=0
total=0
has_text=$(printf '%s\n' "$changed" | grep -cE '\.(java|kt|ts|tsx|js|mjs|cjs|vue|css|scss|md|yml|yaml|json|xml|sql|sh|ps1|properties|html)$' || true)
has_java=$(printf '%s\n' "$changed" | grep -cE '\.java$' || true)
has_md=$(printf '%s\n' "$changed" | grep -cE '\.md$' || true)
has_agents=$(printf '%s\n' "$changed" | grep -cE 'AGENTS\.md$' || true)
has_pom=$(printf '%s\n' "$changed" | grep -cE '(^|/)pom\.xml$' || true)
has_web=$(printf '%s\n' "$changed" | grep -cE '^(web|frontend|ui)/' || true)
# 前端目录：优先取改动文件首段的 web|frontend|ui；否则取 package.json 所在目录（前端在仓库根的场景）。
# 计数与执行条件同源：都只看 has_web_chg，避免「算了但没跑」的静默漏检。
web_dir=$(printf '%s\n' "$changed" | grep -oE '^(web|frontend|ui)/' | head -n 1 | sed 's#/$##')
if [ -z "$web_dir" ] && printf '%s\n' "$changed" | grep -qE '(^|/)package\.json$'; then
  web_dir=$(printf '%s\n' "$changed" | grep -E '(^|/)package\.json$' | head -n 1 | sed 's#/package\.json$##')
fi
has_web_chg=0
if [ "$has_web" -gt 0 ] || printf '%s\n' "$changed" | grep -qE '(^|/)package\.json$'; then
  has_web_chg=1
fi
has_test=$(printf '%s\n' "$changed" | grep -cE '/src/test/.*\.java$' || true)

[ "$has_text" -gt 0 ] && total=$((total + 1))
[ "$has_md" -gt 0 ] && total=$((total + 1))
[ "$has_agents" -gt 0 ] && total=$((total + 1))
[ "$has_java" -gt 0 ] && total=$((total + 2))
[ "$has_test" -gt 0 ] && total=$((total + 1))
[ "$has_pom" -gt 0 ] && total=$((total + 1))
if [ "$has_java" -gt 0 ] || [ "$has_pom" -gt 0 ]; then
  [ -f "$root/pom.xml" ] && total=$((total + 1))
fi
[ "$has_web_chg" -eq 1 ] && total=$((total + 1))

report_fail() {
  echo ""
  echo "!!!! [$1/$total] $2"
  echo "     复跑：$3"
  exit 1
}

# 改动文件清单经标准输入分批交给检查器：积压数十个提交时改动文件可达上千个，
# 一次性当参数传入会超过 Windows 进程参数表上限（约 32 KB）。分批不改变检查范围与判定。
run_checker_on_list() {
  printf '%s\n' "$2" | xargs -d '\n' -r -n 40 node "$1"
}

run_step() {
  step=$((step + 1))
  echo ""
  echo ">>>> [$step/$total] $1"
}

# 中间产物：与改动范围无关，每次推送都查（仓库卫生）
total=$((total + 1))
run_step "中间产物：node .agent/tools/common/check-artifacts.mjs"
if node "$root/.agent/tools/common/check-artifacts.mjs"; then :; else
  report_fail "$step" "版本库混入中间产物" "node .agent/tools/common/check-artifacts.mjs"
fi

# 规范边界：.agent/ 要能被别的项目整包复用，就不能引用包外路径
total=$((total + 1))
run_step "规范边界：node .agent/tools/common/check-scope.mjs"
if node "$root/.agent/tools/common/check-scope.mjs"; then :; else
  report_fail "$step" "规范引用了 .agent 之外的路径" "node .agent/tools/common/check-scope.mjs"
fi

if [ "$has_text" -gt 0 ]; then
  run_step "编码完整性：node .agent/tools/common/check-encoding.mjs"
  if node "$root/.agent/tools/common/check-encoding.mjs"; then :; else
    report_fail "$step" "文件编码被破坏" "node .agent/tools/common/check-encoding.mjs"
  fi
fi

if [ "$has_md" -gt 0 ]; then
  run_step "文档结构：node .agent/tools/common/check-docs.mjs"
  if node "$root/.agent/tools/common/check-docs.mjs"; then :; else
    report_fail "$step" "文档出现禁止的小节" "node .agent/tools/common/check-docs.mjs"
  fi
fi

if [ "$has_agents" -gt 0 ]; then
  run_step "入口指针：node .agent/tools/common/check-agents.mjs"
  if node "$root/.agent/tools/common/check-agents.mjs"; then :; else
    report_fail "$step" "入口文件指针失效" "node .agent/tools/common/check-agents.mjs"
  fi
fi

if [ "$has_java" -gt 0 ]; then
  run_step "Java 形态：check-java-conventions（本次改动）"
  java_files=$(printf '%s\n' "$changed" | grep -E '\.java$' || true)
  if run_checker_on_list "$root/.agent/tools/java/check-java-conventions.mjs" "$java_files"; then :; else
    report_fail "$step" "Java 形态不合规" "node .agent/tools/java/check-java-conventions.mjs <文件>"
  fi

  run_step "Javadoc：check-javadoc（本次改动）"
  if run_checker_on_list "$root/.agent/tools/java/check-javadoc.mjs" "$java_files"; then :; else
    report_fail "$step" "Javadoc 不合规" "node .agent/tools/java/check-javadoc.mjs <文件>"
  fi
fi

if [ "$has_test" -gt 0 ]; then
  run_step "用例质量：check-test-quality（本次改动）"
  test_files=$(printf '%s\n' "$changed" | grep -E '/src/test/.*\.java$' || true)
  if run_checker_on_list "$root/.agent/tools/java/check-test-quality.mjs" "$test_files"; then :; else
    report_fail "$step" "用例质量不达标" "node .agent/tools/java/check-test-quality.mjs <文件>"
  fi
fi

if [ "$has_pom" -gt 0 ]; then
  run_step "模块结构：node .agent/tools/common/check-modules.mjs"
  if node "$root/.agent/tools/common/check-modules.mjs"; then :; else
    report_fail "$step" "模块结构有问题" "node .agent/tools/common/check-modules.mjs"
  fi
fi

if [ "$has_java" -gt 0 ] || [ "$has_pom" -gt 0 ]; then
  if [ -f "$root/pom.xml" ]; then
    run_step "后端全量门禁：mvn -o verify"
    if (cd "$root" && mvn -o -q verify); then :; else
      report_fail "$step" "后端门禁失败" "mvn -o verify"
    fi
  fi
fi

if [ "$has_web_chg" -eq 1 ]; then
  if [ -z "$web_dir" ] || [ ! -f "$root/$web_dir/package.json" ] || ! grep -q '"lint"' "$root/$web_dir/package.json" 2>/dev/null; then
    report_fail "$step" "前端目录缺少带 lint 脚本的 package.json（${web_dir:-未定位}）" "在 ${web_dir:-前端目录} 补 lint 脚本后复跑门禁"
  fi
  run_step "前端校验：包管理器 lint（${web_dir}/）"
  if (cd "$root/$web_dir" && (command -v pnpm >/dev/null 2>&1 && pnpm lint || npm run lint)); then :; else
    report_fail "$step" "前端校验失败" "cd $web_dir && pnpm lint"
  fi
fi

echo ""
echo "推送前门禁：全部通过（$step 步）"
