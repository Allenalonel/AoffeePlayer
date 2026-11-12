[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(1, 2, 3)]
    [int]$Slot,

    [Parameter(Mandatory = $true)]
    [string]$Branch,

    [string]$BaseRef = "HEAD"
)

$ErrorActionPreference = "Stop"
$root = (git rev-parse --show-toplevel).Trim()
$slotPath = "$root/.worktrees/slot-$Slot"

if (-not (Test-Path "$root/.worktrees")) {
    New-Item -ItemType Directory -Path "$root/.worktrees" -Force | Out-Null
}

if (-not (Test-Path $slotPath)) {
    Write-Host "Creating worktree slot $Slot at $slotPath on branch $Branch from $BaseRef..."
    git worktree add -B $Branch "$slotPath" $BaseRef
    if (Test-Path "$root/local.properties") {
        Copy-Item -Force "$root/local.properties" "$slotPath/local.properties"
    }
} else {
    Write-Host "Reusing existing worktree slot $Slot at $slotPath..."
    Push-Location $slotPath
    try {
        git checkout -B $Branch $BaseRef
    } finally {
        Pop-Location
    }
    if ((Test-Path "$root/local.properties") -and -not (Test-Path "$slotPath/local.properties")) {
        Copy-Item -Force "$root/local.properties" "$slotPath/local.properties"
    }
}

Write-Host "Worktree slot $Slot ready at $slotPath on branch $Branch"
