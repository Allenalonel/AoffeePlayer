[CmdletBinding()]
param(
    [string]$PackageName = "com.webdav.player",
    [string]$Serial = $null,
    [switch]$Reset,
    [int]$TargetFps = 60,
    [double]$MaxJankRate = -1,
    [double]$MaxP95Ms = -1,
    [double]$MaxP99Ms = -1
)

$ErrorActionPreference = "Stop"

# 1. Device connectivity check
$adbArgs = @()
if ($Serial) {
    $adbArgs += @("-s", $Serial)
}

$devicesOutput = & adb @adbArgs devices
$deviceLines = $devicesOutput | Where-Object { $_ -match "\tdevice$" }
if (-not $deviceLines) {
    Write-Error "No connected Android device found via 'adb devices'."
    exit 1
}

if (-not $Serial -and $deviceLines.Count -gt 1) {
    Write-Warning "Multiple devices detected. Please specify target device with -Serial <device_id>."
    $deviceLines | ForEach-Object { Write-Host "  $_" }
    exit 1
}

if ($Reset) {
    Write-Host "Resetting gfxinfo framestats for $PackageName..."
    & adb @adbArgs shell "dumpsys gfxinfo $PackageName reset" | Out-Null
    Write-Host "Framestats buffer reset completed."
    if ($MaxJankRate -lt 0 -and $MaxP95Ms -lt 0 -and $MaxP99Ms -lt 0) {
        exit 0
    }
}

Write-Host "Capturing dumpsys gfxinfo framestats for '$PackageName'..."
$rawOutput = & adb @adbArgs shell "dumpsys gfxinfo $PackageName framestats"

if (-not $rawOutput) {
    Write-Error "Failed to obtain gfxinfo output for package '$PackageName'. Is the app running?"
    exit 1
}

# 2. Extract PROFILEDATA block
$lines = $rawOutput -split "`r?`n"
$profileBlocks = [System.Collections.Generic.List[object]]::new()
$inBlock = $false
$currentBlock = [System.Collections.Generic.List[string]]::new()

foreach ($line in $lines) {
    $trimmed = $line.Trim()
    if ($trimmed -eq "---PROFILEDATA---") {
        if ($inBlock) {
            $inBlock = $false
            if ($currentBlock.Count -gt 1) {
                $profileBlocks.Add($currentBlock.ToArray())
            }
            $currentBlock.Clear()
        } else {
            $inBlock = $true
            $currentBlock.Clear()
        }
        continue
    }
    if ($inBlock) {
        $currentBlock.Add($trimmed)
    }
}

if ($profileBlocks.Count -eq 0) {
    Write-Warning "No ---PROFILEDATA--- blocks found in dumpsys gfxinfo output."
    Write-Host "Ensure the app has rendered frames after launch or interaction."
    exit 1
}

# Use the last profile block (most recent window / activity)
$block = $profileBlocks[$profileBlocks.Count - 1]
$headerLine = $block[0]
$headerCols = $headerLine -split ','

$idxFlags = $headerCols.IndexOf('Flags')
$idxIntendedVsync = $headerCols.IndexOf('IntendedVsync')
$idxPerformTraversals = $headerCols.IndexOf('PerformTraversalsStart')
$idxDrawStart = $headerCols.IndexOf('DrawStart')
$idxSyncStart = $headerCols.IndexOf('SyncStart')
$idxIssueDrawCommands = $headerCols.IndexOf('IssueDrawCommandsStart')
$idxSwapBuffers = $headerCols.IndexOf('SwapBuffers')
$idxFrameCompleted = $headerCols.IndexOf('FrameCompleted')

if ($idxFlags -lt 0 -or $idxIntendedVsync -lt 0 -or $idxFrameCompleted -lt 0) {
    Write-Error "Unsupported gfxinfo header columns format: $headerLine"
    exit 1
}

$frameDataList = [System.Collections.Generic.List[PSCustomObject]]::new()
$targetIntervalMs = 1000.0 / $TargetFps

for ($i = 1; $i -lt $block.Count; $i++) {
    $row = $block[$i] -split ','
    if ($row.Count -le $idxFrameCompleted) { continue }

    $flags = [int64]$row[$idxFlags]
    # Filter Flags == 0 (normal steady-state animation frame)
    if ($flags -ne 0) { continue }

    $intendedVsync = [int64]$row[$idxIntendedVsync]
    $frameCompleted = [int64]$row[$idxFrameCompleted]
    if ($intendedVsync -le 0 -or $frameCompleted -le $intendedVsync) { continue }

    $totalTimeMs = ($frameCompleted - $intendedVsync) / 1000000.0

    # Calculate stages
    $inputAnimMs = if ($idxPerformTraversals -ge 0) {
        [math]::Max(0.0, ([int64]$row[$idxPerformTraversals] - $intendedVsync) / 1000000.0)
    } else { 0.0 }

    $traversalMs = if ($idxPerformTraversals -ge 0 -and $idxDrawStart -ge 0) {
        [math]::Max(0.0, ([int64]$row[$idxDrawStart] - [int64]$row[$idxPerformTraversals]) / 1000000.0)
    } else { 0.0 }

    $drawMs = if ($idxDrawStart -ge 0 -and $idxSyncStart -ge 0) {
        [math]::Max(0.0, ([int64]$row[$idxSyncStart] - [int64]$row[$idxDrawStart]) / 1000000.0)
    } else { 0.0 }

    $renderThreadMs = if ($idxSyncStart -ge 0 -and $idxSwapBuffers -ge 0) {
        [math]::Max(0.0, ([int64]$row[$idxSwapBuffers] - [int64]$row[$idxSyncStart]) / 1000000.0)
    } else { 0.0 }

    $swapMs = if ($idxSwapBuffers -ge 0) {
        [math]::Max(0.0, ($frameCompleted - [int64]$row[$idxSwapBuffers]) / 1000000.0)
    } else { 0.0 }

    $isJank = $totalTimeMs -gt $targetIntervalMs

    $frameDataList.Add([PSCustomObject]@{
        TotalMs = $totalTimeMs
        InputAnimMs = $inputAnimMs
        TraversalMs = $traversalMs
        DrawMs = $drawMs
        RenderThreadMs = $renderThreadMs
        SwapMs = $swapMs
        IsJank = $isJank
    })
}

$totalValidFrames = $frameDataList.Count
if ($totalValidFrames -eq 0) {
    Write-Warning "No valid steady-state frames (Flags==0) collected. Please interact with the UI and retry."
    exit 1
}

# 3. Compute Percentiles & Metrics
$sortedTimes = $frameDataList | ForEach-Object { $_.TotalMs } | Sort-Object
function Get-Percentile([double[]]$sorted, [double]$pct) {
    if ($sorted.Length -eq 0) { return 0.0 }
    $index = [math]::Ceiling(($pct / 100.0) * $sorted.Length) - 1
    $index = [math]::Max(0, [math]::Min($index, $sorted.Length - 1))
    return $sorted[$index]
}

$p50 = Get-Percentile $sortedTimes 50.0
$p90 = Get-Percentile $sortedTimes 90.0
$p95 = Get-Percentile $sortedTimes 95.0
$p99 = Get-Percentile $sortedTimes 99.0
$maxMs = $sortedTimes[$sortedTimes.Length - 1]

$jankFrames = ($frameDataList | Where-Object { $_.IsJank }).Count
$jankRate = ($jankFrames / $totalValidFrames) * 100.0

$avgTotal = ($frameDataList | Measure-Object -Property TotalMs -Average).Average
$avgInput = ($frameDataList | Measure-Object -Property InputAnimMs -Average).Average
$avgTraversal = ($frameDataList | Measure-Object -Property TraversalMs -Average).Average
$avgDraw = ($frameDataList | Measure-Object -Property DrawMs -Average).Average
$avgRender = ($frameDataList | Measure-Object -Property RenderThreadMs -Average).Average
$avgSwap = ($frameDataList | Measure-Object -Property SwapMs -Average).Average

Write-Host "`n================ GFXINFO REPORT ($PackageName) ================"
Write-Host ("Target Frame Budget : {0:F2} ms ({1} FPS)" -f $targetIntervalMs, $TargetFps)
Write-Host ("Total Valid Frames  : {0}" -f $totalValidFrames)
Write-Host ("Janky Frames Count  : {0} ({1:F2}%)" -f $jankFrames, $jankRate)
Write-Host "---------------- Frame Duration Percentiles ----------------"
Write-Host ("  P50 (Median)      : {0:F2} ms" -f $p50)
Write-Host ("  P90               : {0:F2} ms" -f $p90)
Write-Host ("  P95               : {0:F2} ms" -f $p95)
Write-Host ("  P99               : {0:F2} ms" -f $p99)
Write-Host ("  Max Frame Time    : {0:F2} ms" -f $maxMs)
Write-Host "---------------- Phase Breakdown (Averages) ----------------"
Write-Host ("  Input & Animation : {0:F2} ms" -f $avgInput)
Write-Host ("  Traversal (Layout): {0:F2} ms" -f $avgTraversal)
Write-Host ("  Draw (DisplayList): {0:F2} ms" -f $avgDraw)
Write-Host ("  RenderThread/GPU  : {0:F2} ms" -f $avgRender)
Write-Host ("  SwapBuffers       : {0:F2} ms" -f $avgSwap)
Write-Host "============================================================`n"

# 4. Bottleneck Diagnosis
$bottlenecks = @()
if ($avgTraversal -gt 4.0) {
    $bottlenecks += "High Traversal/Layout time ($([math]::Round($avgTraversal, 2)) ms). Possible Compose unnecessary recomposition or heavy measure passes."
}
if ($avgDraw -gt 4.0) {
    $bottlenecks += "High Draw time ($([math]::Round($avgDraw, 2)) ms). Check complex Canvas operations, path rendering, or high layer allocation."
}
if ($avgRender -gt 8.0) {
    $bottlenecks += "High RenderThread/GPU time ($([math]::Round($avgRender, 2)) ms). GPU shader or texture upload bottleneck."
}

if ($bottlenecks.Count -gt 0) {
    Write-Warning "POTENTIAL BOTTLENECK DIAGNOSIS:"
    $bottlenecks | ForEach-Object { Write-Host "  * $_" -ForegroundColor Yellow }
}

# 5. Gate Assertions
$failed = $false
if ($MaxJankRate -ge 0.0) {
    if ($jankRate -gt $MaxJankRate) {
        Write-Error ("GATE 3 VIOLATION: Jank rate ({0:F2}%) exceeded limit ({1:F2}%)." -f $jankRate, $MaxJankRate)
        $failed = $true
    } else {
        Write-Host ("PASS: Jank rate ({0:F2}%) is within limit (<= {1:F2}%)." -f $jankRate, $MaxJankRate) -ForegroundColor Green
    }
}

if ($MaxP95Ms -ge 0.0) {
    if ($p95 -gt $MaxP95Ms) {
        Write-Error ("GATE 3 VIOLATION: P95 frame time ({0:F2} ms) exceeded limit ({1:F2} ms)." -f $p95, $MaxP95Ms)
        $failed = $true
    } else {
        Write-Host ("PASS: P95 frame time ({0:F2} ms) is within limit (<= {1:F2} ms)." -f $p95, $MaxP95Ms) -ForegroundColor Green
    }
}

if ($MaxP99Ms -ge 0.0) {
    if ($p99 -gt $MaxP99Ms) {
        Write-Error ("GATE 3 VIOLATION: P99 frame time ({0:F2} ms) exceeded limit ({1:F2} ms)." -f $p99, $MaxP99Ms)
        $failed = $true
    } else {
        Write-Host ("PASS: P99 frame time ({0:F2} ms) is within limit (<= {1:F2} ms)." -f $p99, $MaxP99Ms) -ForegroundColor Green
    }
}

if ($failed) {
    exit 1
}
exit 0
