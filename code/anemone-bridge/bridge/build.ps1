param([string]$AnemoneJar = $env:ANEMONE_JAR)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $AnemoneJar) {
    $line = Get-Content (Join-Path $PSScriptRoot 'bridge.properties') | Where-Object { $_ -match '^anemone.jar=' }
    $AnemoneJar = $line.Substring('anemone.jar='.Length)
}
if (-not (Test-Path -LiteralPath $AnemoneJar -PathType Leaf)) { throw "JAR Anemone introuvable : $AnemoneJar" }
$classes = Join-Path $PSScriptRoot 'build/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -encoding UTF-8 -source 8 -target 8 -cp $AnemoneJar -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation Java échouée' }
Write-Host 'Compilation SmartStock : Anemone'
