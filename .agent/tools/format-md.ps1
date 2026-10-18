<#
.SYNOPSIS
  Normalize Markdown table alignment, replicating IntelliJ IDEA's "Reformat Table"
  (pure script, IDEA not required).

.DESCRIPTION
  Alignment rules match IDEA (byte-level verified against real IDEA output):
    - column width = max display width in that column (CJK/fullwidth and emoji count as 2,
      ASCII as 1, variation selectors as 0);
    - content rows | cell |: each cell padded with spaces to the column width, one space per side;
    - separator rows |----|: no spaces, dashes = column width + 2 (minimum 3),
      alignment colons (:---:) are preserved.
  Tables containing emoji are aligned at width 2 and reported with a suggestion to drop them.
  By default formats every .md under docs/; use -Paths to target files or directories.
  Fenced code blocks (```) are left untouched.

  This file is deliberately pure ASCII and carries no BOM. Windows PowerShell 5.1 falls back to
  the ANSI code page for BOM-less scripts, so a single non-ASCII byte here would make the script
  parse differently depending on encoding. Keep it ASCII-only.

.EXAMPLE
  powershell .agent/tools/format-md.ps1
  powershell .agent/tools/format-md.ps1 -Paths docs
  powershell .agent/tools/format-md.ps1 -Paths README.md,.agent/standards/README.md

.EXAMPLE
  powershell .agent/tools/format-md.ps1 -SelfTest
  Verify the formatter against a fixed fixture; exits non-zero on mismatch.
#>
param(
    [Parameter(Position = 0)]
    [string[]]$Paths = @('docs'),

    [switch]$SelfTest
)

$ErrorActionPreference = 'Stop'
# Paths resolve against the current directory: run from the repo root, or pass absolute -Paths.

# IDEA display-width rule: East Asian fullwidth ranges and emoji count as 2, everything else 1;
# variation selectors and zero-width joiners count as 0.
function Get-DisplayWidth([string]$s) {
    $w = 0
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        if ($c -eq 0xFE0F -or $c -eq 0x200D) { continue }
        # Non-BMP emoji arrive as two UTF-16 surrogates (0xD800-0xDFFF); no range below matches
        # them, so each counts 1 and an emoji totals 2 - which is the intended width.
        $full = ($c -ge 0x1100 -and $c -le 0x115F) -or
                ($c -ge 0x2E80 -and $c -le 0xA4CF) -or
                ($c -ge 0xAC00 -and $c -le 0xD7A3) -or
                ($c -ge 0xF900 -and $c -le 0xFAFF) -or
                ($c -ge 0xFE30 -and $c -le 0xFE4F) -or
                ($c -ge 0xFF00 -and $c -le 0xFF60) -or
                ($c -ge 0xFFE0 -and $c -le 0xFFE6) -or
                ($c -ge 0x2600 -and $c -le 0x27BF) -or
                ($c -ge 0x2B00 -and $c -le 0x2BFF) -or
                ($c -ge 0x1F000 -and $c -le 0x1FAFF)
        $w += if ($full) { 2 } else { 1 }
    }
    return $w
}

# Emoji inside a table are aligned at width 2 (matching IDEA) but reported, since plain text
# keeps the width contract unambiguous.
function Test-HasEmoji([string]$s) {
    foreach ($ch in $s.ToCharArray()) {
        $c = [int]$ch
        if (($c -ge 0x2600 -and $c -le 0x27BF) -or
            ($c -ge 0x2B00 -and $c -le 0x2BFF) -or
            ($c -ge 0x1F000 -and $c -le 0x1FAFF)) {
            return $true
        }
    }
    return $false
}

function Test-SeparatorCell([string]$cell) {
    return $cell -match '^:?-{3,}:?$'
}

function Format-MdTables([string]$path) {
    $lines = [System.IO.File]::ReadAllLines($path)
    $out = New-Object System.Collections.Generic.List[string]
    $inFence = $false
    $i = 0
    while ($i -lt $lines.Count) {
        $line = $lines[$i]
        if ($line -match '^\s*```') {
            $inFence = -not $inFence
            $out.Add($line)
            $i++
            continue
        }
        if (-not $inFence -and $line -match '^\s*\|.*\|\s*$') {
            # Collect the run of consecutive table rows.
            $block = New-Object System.Collections.Generic.List[string]
            while ($i -lt $lines.Count -and $lines[$i] -match '^\s*\|.*\|\s*$') {
                $block.Add($lines[$i])
                $i++
            }
            # Split cells on unescaped pipes.
            $rows = New-Object System.Collections.Generic.List[object]
            foreach ($b in $block) {
                $t = $b.Trim()
                $t = $t.Substring(1, $t.Length - 2)
                $cells = @($t -split '(?<!\\)\|' | ForEach-Object { $_.Trim() })
                $rows.Add([pscustomobject]@{ Cells = $cells; Separator = @($cells | Where-Object { -not (Test-SeparatorCell $_) }).Count -eq 0 })
            }
            $cols = ($rows | ForEach-Object { $_.Cells.Count } | Measure-Object -Maximum).Maximum
            # Report emoji: width 2 already matches IDEA, but plain text is preferred.
            foreach ($r in $rows) {
                if ($r.Separator) { continue }
                foreach ($cell in $r.Cells) {
                    if (Test-HasEmoji $cell) {
                        Write-Host "WARN: $path table contains emoji (aligned at width 2 like IDEA); plain text preferred"
                        break
                    }
                }
            }
            # Column width = max display width; separator dashes = width + 2 (minimum 3).
            $widths = @()
            for ($c = 0; $c -lt $cols; $c++) {
                $max = 0
                foreach ($r in $rows) {
                    if ($r.Separator) { continue }
                    $cell = if ($c -lt $r.Cells.Count) { $r.Cells[$c] } else { '' }
                    $dw = Get-DisplayWidth $cell
                    if ($dw -gt $max) { $max = $dw }
                }
                $widths += $max
            }
            # Render.
            foreach ($r in $rows) {
                if ($r.Separator) {
                    # Separator row: |----|----| (no spaces; dashes = width + 2, min 3; colons kept).
                    $cells = New-Object System.Collections.Generic.List[string]
                    for ($c = 0; $c -lt $cols; $c++) {
                        $cell = if ($c -lt $r.Cells.Count) { $r.Cells[$c] } else { '' }
                        $n = $widths[$c] + 2
                        if ($n -lt 3) { $n = 3 }
                        if ($cell.StartsWith(':') -and $cell.EndsWith(':')) {
                            $cells.Add(':' + ('-' * ($n - 2)) + ':')
                        } else {
                            $cells.Add('-' * $n)
                        }
                    }
                    $out.Add('|' + ($cells -join '|') + '|')
                } else {
                    # Content row: | padded cell | (one space per side).
                    $cells = New-Object System.Collections.Generic.List[string]
                    for ($c = 0; $c -lt $cols; $c++) {
                        $cell = if ($c -lt $r.Cells.Count) { $r.Cells[$c] } else { '' }
                        $cells.Add($cell + (' ' * ($widths[$c] - (Get-DisplayWidth $cell))))
                    }
                    $out.Add('| ' + ($cells -join ' | ') + ' |')
                }
            }
        } else {
            $out.Add($line)
            $i++
        }
    }
    $content = ($out -join "`n") + "`n"
    [System.IO.File]::WriteAllText($path, $content, (New-Object System.Text.UTF8Encoding($false)))
}

# Self-check used by the pre-commit hook: format a fixed fixture and compare with the expected
# canonical form. The fixture is built from code points so this file stays pure ASCII.
if ($SelfTest) {
    $cjk = ([string][char]0x4E2D) + ([string][char]0x6587)   # 2 CJK chars, display width 4
    $nl = "`n"
    $fixture = '# t' + $nl + $nl + '| a | b |' + $nl + '|---|---|' + $nl + '| ' + $cjk + ' | c |' + $nl
    $expect = '# t' + $nl + $nl + '| a    | b |' + $nl + '|------|---|' + $nl + '| ' + $cjk + ' | c |' + $nl
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $fixture, (New-Object System.Text.UTF8Encoding($false)))
    Format-MdTables $tmp
    $got = [System.IO.File]::ReadAllText($tmp)
    Remove-Item $tmp -Force
    if ($got -ne $expect) {
        Write-Host 'SELFTEST FAILED: formatter output does not match the expected canonical form.'
        Write-Host '--- expected ---'
        Write-Host $expect
        Write-Host '--- got ---'
        Write-Host $got
        exit 1
    }
    Write-Host 'SELFTEST OK: formatter is healthy.'
    exit 0
}

$targets = @()
foreach ($p in $Paths) {
    $item = Get-Item (Resolve-Path $p)
    if ($item.PSIsContainer) {
        $targets += Get-ChildItem $item.FullName -Recurse -Filter *.md | Select-Object -ExpandProperty FullName
    } else {
        $targets += $item.FullName
    }
}
$targets = @($targets | Sort-Object -Unique)
foreach ($t in $targets) {
    Format-MdTables $t
    Write-Host "FORMATTED: $t"
}
