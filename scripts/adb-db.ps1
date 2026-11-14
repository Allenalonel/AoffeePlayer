[CmdletBinding()]
param(
    [string]$PackageName = "com.webdav.player",
    [string]$DbName = "webdav_player.db",
    [string]$Serial = $null,
    [string]$Query = $null,
    [string]$Table = $null,
    [switch]$ListTables,
    [int]$AssertMinCount = -1
)

$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel).Trim()
$dbDir = "$root/.tmp/db"

if (-not (Test-Path $dbDir)) {
    New-Item -ItemType Directory -Path $dbDir -Force | Out-Null
}

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

Write-Host "Target Package: $PackageName"
Write-Host "Target Database: $DbName"

# 2. Check databases directory via run-as
$listCmd = "run-as $PackageName ls /data/data/$PackageName/databases/ 2>/dev/null"
$existingFilesRaw = & adb @adbArgs shell $listCmd
if ($LASTEXITCODE -ne 0 -or -not $existingFilesRaw) {
    Write-Error "Failed to access databases directory via 'run-as $PackageName'. Ensure the app is installed with debuggable variant (assembleDebug)."
    exit 1
}

$dbPrefix = [System.IO.Path]::GetFileNameWithoutExtension($DbName)
$matchedFiles = $existingFilesRaw -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object {
    $_ -and ($_ -eq $DbName -or $_ -like "$dbPrefix.*" -or $_ -like "$DbName-*")
}

if (-not $matchedFiles -or ($matchedFiles -notcontains $DbName)) {
    Write-Error "Database file '$DbName' was not found in /data/data/$PackageName/databases/."
    Write-Host "Available files in databases directory:"
    $existingFilesRaw -split "`r?`n" | ForEach-Object { if ($_) { Write-Host "  - $_" } }
    exit 1
}

Write-Host "Extracting database files (including WAL/SHM)..."
foreach ($file in $matchedFiles) {
    $remoteDbPath = "/data/data/$PackageName/databases/$file"
    $remoteTmpPath = "/data/local/tmp/$file"
    $localFilePath = "$dbDir/$file"

    # Shell pre-creates world-writable staging file in /data/local/tmp
    & adb @adbArgs shell "touch $remoteTmpPath && chmod 666 $remoteTmpPath" | Out-Null
    # App streams content into staging file via run-as
    & adb @adbArgs shell "run-as $PackageName sh -c 'cat $remoteDbPath > $remoteTmpPath'" | Out-Null

    & adb @adbArgs pull $remoteTmpPath $localFilePath | Out-Null
    & adb @adbArgs shell "rm -f $remoteTmpPath" | Out-Null
    Write-Host "  Pulled: $file -> $localFilePath"
}

$localMainDb = "$dbDir/$DbName"
if (-not (Test-Path $localMainDb)) {
    Write-Error "Primary database file failed to pull to $localMainDb."
    exit 1
}

Write-Host "`nDatabase extracted successfully to: $localMainDb"

# 3. SQLite Inspection via host sqlite3 CLI if available
$sqliteCmd = Get-Command sqlite3 -ErrorAction SilentlyContinue
if (-not $sqliteCmd) {
    Write-Warning "Host command 'sqlite3' not found in PATH."
    Write-Host "Database files are available under: $dbDir"
    Write-Host "You can open '$localMainDb' with DB Browser for SQLite or VS Code SQLite extension."
    if ($Query -or $Table -or $ListTables -or ($AssertMinCount -ge 0)) {
        Write-Error "Cannot execute requested SQL query or assertion because 'sqlite3' CLI is not available."
        exit 1
    }
    exit 0
}

# Helper to run sqlite query
function Invoke-Sql([string]$sql) {
    & sqlite3 $localMainDb -header -column $sql
}

if ($ListTables) {
    Write-Host "`n--- Tables in $DbName ---"
    & sqlite3 $localMainDb ".tables"
}

if ($Table) {
    Write-Host "`n--- Table: $Table ---"
    $countStr = (& sqlite3 $localMainDb "SELECT count(*) FROM $Table;").Trim()
    Write-Host "Total rows: $countStr"

    Write-Host "`nSchema:"
    & sqlite3 $localMainDb ".schema $Table"

    Write-Host "`nSample data (LIMIT 5):"
    Invoke-Sql "SELECT * FROM $Table LIMIT 5;"

    if ($AssertMinCount -ge 0) {
        $count = [int]$countStr
        if ($count -lt $AssertMinCount) {
            Write-Error "ASSERTION FAILED: Table '$Table' has $count records, expected at least $AssertMinCount."
            exit 1
        } else {
            Write-Host "`nASSERTION PASSED: Table '$Table' has $count records (>= $AssertMinCount)." -ForegroundColor Green
        }
    }
} elseif ($AssertMinCount -ge 0) {
    Write-Error "-AssertMinCount requires -Table <tableName> to be specified."
    exit 1
}

if ($Query) {
    Write-Host "`n--- Query Execution ---"
    Write-Host "SQL: $Query"
    Invoke-Sql $Query
}
