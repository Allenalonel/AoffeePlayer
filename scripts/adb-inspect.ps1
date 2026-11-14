[CmdletBinding()]
param(
    [string]$Tag = "snapshot",
    [string]$Crop = $null,
    [string]$Target = $null
)

$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel).Trim()
$inspectDir = "$root/.tmp/inspect"

if (-not (Test-Path $inspectDir)) {
    New-Item -ItemType Directory -Path $inspectDir -Force | Out-Null
}

# 1. Device connectivity check
$devicesOutput = adb devices
$deviceLines = $devicesOutput | Where-Object { $_ -match "\tdevice$" }
if (-not $deviceLines) {
    Write-Error "No connected Android device found via 'adb devices'."
    return
}

$screenshotDevicePath = "/sdcard/inspect_$Tag.png"
$dumpDevicePath = "/sdcard/inspect_$Tag.xml"
$localPng = "$inspectDir/$Tag.png"
$localXml = "$inspectDir/$Tag.xml"

Write-Host "Capturing screen and UI hierarchy for tag '$Tag'..."

# 2. Screencap
adb shell screencap -p $screenshotDevicePath
adb pull $screenshotDevicePath $localPng | Out-Null
adb shell rm -f $screenshotDevicePath

# 3. UI Automator Dump
adb shell uiautomator dump $dumpDevicePath | Out-Null
adb pull $dumpDevicePath $localXml | Out-Null
adb shell rm -f $dumpDevicePath

Write-Host "Snapshot saved to:"
Write-Host "  Image: $localPng"
Write-Host "  Dump:  $localXml"

# 4. Geometry & Alignment Analysis
if (Test-Path $localXml) {
    [xml]$xml = Get-Content $localXml -Encoding UTF8

    function Parse-Bounds([string]$boundsStr) {
        if ($boundsStr -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $x1 = [int]$matches[1]
            $y1 = [int]$matches[2]
            $x2 = [int]$matches[3]
            $y2 = [int]$matches[4]
            return [PSCustomObject]@{
                Left = $x1
                Top = $y1
                Right = $x2
                Bottom = $y2
                Width = ($x2 - $x1)
                Height = ($y2 - $y1)
                CenterX = ($x1 + $x2) / 2.0
                CenterY = ($y1 + $y2) / 2.0
            }
        }
        return $null
    }

    $allNodes = $xml.SelectNodes("//node")
    $elementMap = @{}

    $searchDesc = @("上一首", "下一首", "播放", "暂停", "播放队列", "专辑封面", "列表循环", "单曲循环", "随机播放", "收起")
    foreach ($node in $allNodes) {
        $desc = $node.GetAttribute("content-desc")
        $text = $node.GetAttribute("text")
        $boundsStr = $node.GetAttribute("bounds")

        if ($desc -and ($searchDesc -contains $desc)) {
            if (-not $elementMap.ContainsKey($desc)) {
                $b = Parse-Bounds $boundsStr
                if ($b) { $elementMap[$desc] = $b }
            }
        }

        if ($Target -and (($desc -like "*$Target*") -or ($text -like "*$Target*"))) {
            $b = Parse-Bounds $boundsStr
            Write-Host "Target match ['$Target']: desc='$desc', text='$text', bounds=$boundsStr, Center=($($b.CenterX), $($b.CenterY))"
        }
    }

    Write-Host "`n--- Player Key Elements Geometry ---"
    foreach ($key in $elementMap.Keys) {
        $b = $elementMap[$key]
        Write-Host ("{0,-8}: bounds=[{1},{2}][{3},{4}] size={5}x{6} Center=({7:F1}, {8:F1})" -f $key, $b.Left, $b.Top, $b.Right, $b.Bottom, $b.Width, $b.Height, $b.CenterX, $b.CenterY)
    }

    # Check horizontal alignment for transport controls (Previous, Play/Pause, Next)
    $prev = $elementMap["上一首"]
    $next = $elementMap["下一首"]
    $play = if ($elementMap.ContainsKey("播放")) { $elementMap["播放"] } else { $elementMap["暂停"] }

    if ($prev -and $play -and $next) {
        $deltaYPrevPlay = [math]::Abs($play.CenterY - $prev.CenterY)
        $deltaYNextPlay = [math]::Abs($play.CenterY - $next.CenterY)

        Write-Host "`n--- Transport Alignment Check ---"
        if ($deltaYPrevPlay -gt 2.0 -or $deltaYNextPlay -gt 2.0) {
            Write-Warning ("ALIGNMENT DEFECT DETECTED: Play button CenterY ({0:F1}) is misaligned with Prev ({1:F1}) or Next ({2:F1}). Delta: {3:F1}px" -f $play.CenterY, $prev.CenterY, $next.CenterY, [math]::Max($deltaYPrevPlay, $deltaYNextPlay))
        } else {
            Write-Host "PASS: Previous, Play/Pause, and Next buttons are horizontally aligned on CenterY: $($play.CenterY)" -ForegroundColor Green
        }
    }
}

# 5. Optional Cropping
if ($Crop) {
    Add-Type -AssemblyName System.Drawing
    $cropParts = $Crop.Split(',') | ForEach-Object { [int]$_.Trim() }
    if ($cropParts.Count -eq 4) {
        $x = $cropParts[0]
        $y = $cropParts[1]
        $w = $cropParts[2]
        $h = $cropParts[3]

        $srcBmp = [System.Drawing.Bitmap]::FromFile($localPng)
        try {
            $cropBmp = New-Object System.Drawing.Bitmap $w, $h
            $g = [System.Drawing.Graphics]::FromImage($cropBmp)
            try {
                $srcRect = New-Object System.Drawing.Rectangle $x, $y, $w, $h
                $destRect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
                $g.DrawImage($srcBmp, $destRect, $srcRect, [System.Drawing.GraphicsUnit]::Pixel)
                $cropPath = "$inspectDir/${Tag}_crop.png"
                $cropBmp.Save($cropPath, [System.Drawing.Imaging.ImageFormat]::Png)
                Write-Host "Cropped region saved to: $cropPath"
            } finally {
                $g.Dispose()
                $cropBmp.Dispose()
            }
        } finally {
            $srcBmp.Dispose()
        }
    } else {
        Write-Warning "Crop parameter must be in 'x,y,w,h' format (e.g. -Crop '100,200,300,400')."
    }
}
