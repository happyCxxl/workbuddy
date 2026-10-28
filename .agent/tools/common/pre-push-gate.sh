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
has_text=$(printf '%s\n' "$changed" | grep -cE '\.(java|kt|ts|tsx|js|mjs|cjs|vue|css|scss|md|yml|yaml|json|xml|sql|sh|ps1|properties|html|py|toml)$' || true)
has_java=$(printf '%s\n' "$changed" | grep -cE '\.java$' || true)
has_md=$(printf '%s\n' "$changed" | grep -cE '\.md$' || true)
has_agents=$(printf '%s\n' "$changed" | grep -cE 'AGENTS\.md$' || true)
has_pom=$(printf '%s\n' "$changed" | grep -cE '(^|/)pom\.xml$' || true)
# 工程根定位：按构建文件特征探测，不写死目录名；探测不到或歧义就显式失败，不静默跳过。
#   Maven 根：仓库根有 pom.xml 用它；否则取唯一的一级子目录 pom.xml。
mvn_root=''
if [ -f "$root/pom.xml" ]; then
  mvn_root='.'
else
  for p in "$root"/*/pom.xml; do
    [ -f "$p" ] || continue
    if [ -n "$mvn_root" ]; then
      echo "推送前门禁：存在多个 Maven 工程根（${mvn_root%/pom.xml}、${p%/pom.xml}），无法自动定位"
      exit 1
    fi
    mvn_root=${p%/pom.xml}
    mvn_root=${mvn_root#"$root"/}
  done
fi
# 前端目录：改动落在哪个含 package.json 的目录，就在哪个目录跑 lint（仓库根或一级子目录）。
# 与旧口径的差别只是「目录名不再写死」；package.json 缺 lint 脚本时仍在执行处显式失败。
web_dir=''
for pkg in "$root/package.json" "$root"/*/package.json; do
  [ -f "$pkg" ] || continue
  d=${pkg%/package.json}
  d=${d#"$root"}
  d=${d#/}
  [ -n "$d" ] || d='.'
  if [ "$d" = '.' ]; then
    hits=$(printf '%s\n' "$changed" | grep -cE '(^|/)(package\.json|index\.html)$|\.(vue|tsx?|jsx?|css|scss|less)$' || true)
  else
    hits=$(printf '%s\n' "$changed" | grep -cE "^$d/" || true)
  fi
  if [ "$hits" -gt 0 ]; then
    web_dir=$d
    break
  fi
done
has_web_chg=0
if [ -n "$web_dir" ]; then
  has_web_chg=1
fi
# .vue 只可能出现在前端：改动含 .vue 却没定位到前端目录时显式失败，避免「改了前端但门禁没跑」。
if [ -z "$web_dir" ] && printf '%s\n' "$changed" | grep -qE '\.vue$'; then
  echo "推送前门禁：改动含 .vue 文件，但未定位到含 package.json 的前端目录"
  exit 1
fi
has_py=$(printf '%s\n' "$changed" | grep -cE '\.py$' || true)
has_pytoml=$(printf '%s\n' "$changed" | grep -cE '(^|/)pyproject\.toml$' || true)
# Python 项目目录：优先取改动里 pyproject.toml / uv.lock 所在目录，否则取 .py 文件首段目录。
py_dir=$(printf '%s\n' "$changed" | grep -E '(^|/)pyproject\.toml$' | head -n 1 | sed 's#/pyproject\.toml$##')
if [ -z "$py_dir" ]; then
  py_dir=$(printf '%s\n' "$changed" | grep -E '(^|/)uv\.lock$' | head -n 1 | sed 's#/uv\.lock$##')
fi
if [ -z "$py_dir" ]; then
  py_dir=$(printf '%s\n' "$changed" | grep -E '\.py$' | head -n 1 | sed -E 's#^([^/]+)/.*$#\1#')
fi
has_py_chg=0
if [ "$has_py" -gt 0 ] || [ "$has_pytoml" -gt 0 ]; then
  has_py_chg=1
fi
has_test=$(printf '%s\n' "$changed" | grep -cE '/src/test/.*\.java$' || true)

[ "$has_text" -gt 0 ] && total=$((total + 1))
[ "$has_md" -gt 0 ] && total=$((total + 1))
[ "$has_agents" -gt 0 ] && total=$((total + 1))
[ "$has_java" -gt 0 ] && total=$((total + 2))
[ "$has_test" -gt 0 ] && total=$((total + 1))
[ "$has_pom" -gt 0 ] && total=$((total + 1))
if [ "$has_java" -gt 0 ] || [ "$has_pom" -gt 0 ]; then
  total=$((total + 1))
fi
[ "$has_web_chg" -eq 1 ] && total=$((total + 1))
[ "$has_py_chg" -eq 1 ] && total=$((total + 1))
# 无条件步骤（与改动范围无关，每次推送都跑）也在此处一并计数：
# total 必须在首个 run_step 之前定稿，进度条全程显示同一个分母。
total=$((total + 1)) # 中间产物
total=$((total + 1)) # 规范边界
# 项目层验证器：仓库自带 .project/tools/check-project-docs.mjs 时每次推送都跑（档案漂移兜底）。
has_project_docs=0
if [ -f "$root/.project/tools/check-project-docs.mjs" ]; then
  has_project_docs=1
  total=$((total + 1))
fi

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
run_step "中间产物：node .agent/tools/common/check-artifacts.mjs"
if node "$root/.agent/tools/common/check-artifacts.mjs"; then :; else
  report_fail "$step" "版本库混入中间产物" "node .agent/tools/common/check-artifacts.mjs"
fi

# 规范边界：.agent/ 要能被别的项目整包复用，就不能引用包外路径
run_step "规范边界：node .agent/tools/common/check-scope.mjs"
if node "$root/.agent/tools/common/check-scope.mjs"; then :; else
  report_fail "$step" "规范引用了 .agent 之外的路径" "node .agent/tools/common/check-scope.mjs"
fi

# 项目档案：仓库自带项目层验证器则运行（存在性探测，不写死项目语义）
if [ "$has_project_docs" -eq 1 ]; then
  run_step "项目档案：node .project/tools/check-project-docs.mjs"
  if node "$root/.project/tools/check-project-docs.mjs"; then :; else
    report_fail "$step" "项目档案与仓库现状不一致" "node .project/tools/check-project-docs.mjs"
  fi
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
  if [ -n "$mvn_root" ]; then
    run_step "后端全量门禁：mvn -o -q verify（$mvn_root/）"
    if (cd "$root/$mvn_root" && mvn -o -q verify); then :; else
      report_fail "$step" "后端门禁失败" "cd $mvn_root && mvn -o -q verify"
    fi
  else
    report_fail "$step" "未找到 Maven 工程根（pom.xml）" "在仓库根或唯一的一级子目录放 pom.xml 后复跑"
  fi
fi

if [ "$has_web_chg" -eq 1 ]; then
  if ! grep -q '"lint"' "$root/$web_dir/package.json" 2>/dev/null; then
    report_fail "$step" "前端目录缺少带 lint 脚本的 package.json（$web_dir/package.json）" "在 $web_dir 补 lint 脚本后复跑门禁"
  fi
  run_step "前端校验：包管理器 lint（${web_dir}/）"
  if (cd "$root/$web_dir" && (command -v pnpm >/dev/null 2>&1 && pnpm lint || npm run lint)); then :; else
    report_fail "$step" "前端校验失败" "cd $web_dir && pnpm lint"
  fi
fi

if [ "$has_py_chg" -eq 1 ]; then
  if [ -z "$py_dir" ] || [ ! -f "$root/$py_dir/pyproject.toml" ]; then
    report_fail "$step" "Python 目录缺少 pyproject.toml（${py_dir:-未定位}）" "在 ${py_dir:-Python 目录} 补 pyproject.toml 后复跑门禁"
  fi
  run_step "Python 校验：ruff + pyright + pytest（${py_dir}/）"
  if (cd "$root/$py_dir" && command -v uv >/dev/null 2>&1 && uv sync --frozen --quiet && uv run ruff check && uv run ruff format --check && uv run pyright && uv run pytest); then :; else
    report_fail "$step" "Python 校验失败（需要 uv；改动依赖后先本地 uv sync 更新锁文件）" "cd $py_dir && uv sync && uv run ruff check && uv run ruff format --check && uv run pyright && uv run pytest"
  fi
fi

echo ""
echo "推送前门禁：全部通过（$step 步）"
