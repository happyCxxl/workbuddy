# 本地基础设施健康检查：容器状态 + 端口连通性 + 各组件可用性
# 用法：pwsh -File .\healthcheck.ps1
$compose = Join-Path $PSScriptRoot 'docker-compose.yml'
$ok = 0; $bad = 0

function Report($name, $good, $detail) {
    if ($good) { $script:ok++; Write-Host ("  [OK]   {0,-24} {1}" -f $name, $detail) -ForegroundColor Green }
    else       { $script:bad++; Write-Host ("  [FAIL] {0,-24} {1}" -f $name, $detail) -ForegroundColor Red }
}

Write-Host "`n=== 容器状态 ===" -ForegroundColor Cyan
docker compose -f $compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"

Write-Host "`n=== 端口连通性 ===" -ForegroundColor Cyan
foreach ($p in 3306, 6379, 19530, 9091, 9000, 9001) {
    $c = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    $proc = if ($c) { (Get-Process -Id $c[0].OwningProcess -ErrorAction SilentlyContinue).ProcessName } else { '' }
    Report "port $p" ($null -ne $c) $(if ($c) { "listening ($proc)" } else { 'not listening' })
}

Write-Host "`n=== 组件功能 ===" -ForegroundColor Cyan

# Redis
$r = docker exec knowledge-redis redis-cli ping 2>&1
Report 'redis PING' ($r -match 'PONG') "$r"

# Milvus 健康端点
try {
    $h = Invoke-WebRequest -Uri 'http://localhost:9091/healthz' -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
    Report 'milvus /healthz' ($h.Content -match 'OK') "HTTP $($h.StatusCode) $($h.Content)"
} catch { Report 'milvus /healthz' $false $_.Exception.Message }

# Milvus 内部 MinIO（无宿主机端口，走容器网络）
$m = docker exec milvus-minio sh -c 'mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null 2>&1 && mc ls local >/dev/null 2>&1 && echo LIVEOK' 2>&1
Report 'milvus-minio live' ($m -match 'LIVEOK') "$m"

# knowledge-minio S3 写入 / 读取 / 删除
$mc = docker exec knowledge-minio sh -c @'
set -e
mc alias set local http://localhost:9000 minioadmin minioadmin >/dev/null
mc mb --ignore-existing local/healthcheck >/dev/null
echo "probe-$(date +%s)" > /tmp/probe.txt
mc cp /tmp/probe.txt local/healthcheck/ >/dev/null
mc cat local/healthcheck/probe.txt
mc rm local/healthcheck/probe.txt >/dev/null
mc rb local/healthcheck >/dev/null
'@ 2>&1
Report 'knowledge-minio S3 读写' ($mc -match 'probe-\d+') $(if ($mc -match 'probe-(\d+)') { "读写+删桶成功 ($($mc -replace '\s+$',''))" } else { "$mc" })

# MySQL
$my = docker exec knowledge-mysql mysqladmin ping -h localhost -proot123 2>&1
Report 'mysql ping' ($my -match 'mysqld is alive') "$my"
if ($my -match 'alive') {
    $t = docker exec knowledge-mysql mysql -uroot -proot123 -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='knowledge';" 2>&1
    Report 'mysql knowledge 表数' ($t -match '^\d+$' -and [int]$t -gt 0) "$t 张表"
}

Write-Host "`n=== 结果: $ok 项正常, $bad 项异常 ===" -ForegroundColor $(if ($bad -eq 0) { 'Green' } else { 'Yellow' })
exit $(if ($bad -eq 0) { 0 } else { 1 })
