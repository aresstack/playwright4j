param(
    [string] $RepositoryZipUrl = "https://github.com/microsoft/playwright-java/archive/refs/tags/v1.59.0.zip",
    [string] $UpstreamTestRoot = "playwright-java-contract-tests/src/upstreamTest"
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$tempRoot = Join-Path $repoRoot ".tmp/upstream-playwright-java-tests"
$zipFile = Join-Path $tempRoot "playwright-java-main.zip"
$extractRoot = Join-Path $tempRoot "extract"
$targetRoot = Join-Path $repoRoot $UpstreamTestRoot
$targetJava = Join-Path $targetRoot "java"
$targetResources = Join-Path $targetRoot "resources"

Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $tempRoot | Out-Null
New-Item -ItemType Directory -Force $targetRoot | Out-Null

Write-Host "Downloading $RepositoryZipUrl"
curl.exe -L $RepositoryZipUrl -o $zipFile

Write-Host "Extracting $zipFile"
Expand-Archive -Path $zipFile -DestinationPath $extractRoot -Force

$repoDir = Get-ChildItem -Path $extractRoot -Directory | Select-Object -First 1
if ($null -eq $repoDir) {
    throw "Could not find extracted repository directory under $extractRoot"
}

$sourceTestRoot = Join-Path $repoDir.FullName "playwright/src/test"
$sourceJava = Join-Path $sourceTestRoot "java"
$sourceResources = Join-Path $sourceTestRoot "resources"

if (-not (Test-Path $sourceJava)) {
    throw "Could not find upstream test sources at $sourceJava"
}

Write-Host "Replacing $targetJava"
Remove-Item -Recurse -Force $targetJava -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Split-Path $targetJava -Parent) | Out-Null
Copy-Item -Recurse -Force $sourceJava $targetJava

if (Test-Path $sourceResources) {
    Write-Host "Replacing $targetResources"
    Remove-Item -Recurse -Force $targetResources -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Split-Path $targetResources -Parent) | Out-Null
    Copy-Item -Recurse -Force $sourceResources $targetResources
} else {
    Write-Host "No upstream test resources found. Removing $targetResources if present."
    Remove-Item -Recurse -Force $targetResources -ErrorAction SilentlyContinue
}

Remove-Item -Recurse -Force $tempRoot -ErrorAction SilentlyContinue

$javaCount = (Get-ChildItem -Path $targetJava -Recurse -Filter *.java | Measure-Object).Count
$resourceCount = 0
if (Test-Path $targetResources) {
    $resourceCount = (Get-ChildItem -Path $targetResources -Recurse -File | Measure-Object).Count
}

Write-Host "Imported upstream Playwright Java tests. Java files: $javaCount, resource files: $resourceCount"
