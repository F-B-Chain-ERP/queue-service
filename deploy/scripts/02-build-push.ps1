<#
.SYNOPSIS
    Build Docker Image Queue Service từ máy Local và tải trực tiếp lên Server qua SSH.
.DESCRIPTION
    Script thực hiện:
    1. docker build image queue-service từ root ERP-UTT
    2. docker save & ssh docker load lên server
.EXAMPLE
    .\queue-service\deploy\scripts\02-build-push.ps1 -Tag "latest"
#>

[CmdletBinding()]
param (
    [string]$Tag = "latest",
    [string]$ServerIp = "163.61.72.183",
    [string]$ServerUser = "devops"
)

$ErrorActionPreference = "Stop"
$ImageName = "ghcr.io/f-b-chain-erp/erp-queue:$Tag"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  BUILD & LOAD IMAGE QUEUE SERVICE LÊN SERVER (POWERSHELL)" -ForegroundColor Cyan
Write-Host "  Image        : $ImageName" -ForegroundColor Yellow
Write-Host "  Server Target: $ServerUser@$ServerIp" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# Kiểm tra thư mục chạy
$RootDir = Get-Location
if (Test-Path "queue-service" -PathType Container -and Test-Path "core-model" -PathType Container) {
    # Đúng thư mục root monorepo
} elseif (Test-Path "..\queue-service" -PathType Container -and Test-Path "..\core-model" -PathType Container) {
    Set-Location ".."
    $RootDir = Get-Location
} else {
    Write-Error "[!] Vui lòng đứng tại thư mục root monorepo ERP-UTT để chạy script!"
    exit 1
}

Write-Host "`n▶ 1. Đang build Docker Image từ root..." -ForegroundColor Green
docker build -f queue-service/Dockerfile -t $ImageName .
if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ Build image thất bại!"
    exit $LASTEXITCODE
}

Write-Host "`n▶ 2. Đang truyền Docker Image qua SSH đến $ServerUser@$ServerIp..." -ForegroundColor Green
& docker save $ImageName | ssh "$ServerUser@$ServerIp" "docker load"
if ($LASTEXITCODE -ne 0) {
    Write-Error "❌ Truyền image qua SSH thất bại!"
    exit $LASTEXITCODE
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "  ✅ HOÀN TẤT: Image $ImageName đã sẵn sàng trên Server!" -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Cyan
