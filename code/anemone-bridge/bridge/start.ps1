param([int]$Port = 8080, [string]$AnemoneJar = $env:ANEMONE_JAR)
$ErrorActionPreference = 'Stop'
if (-not $AnemoneJar) {
    $line = Get-Content (Join-Path $PSScriptRoot 'bridge.properties') | Where-Object { $_ -match '^anemone.jar=' }
    $AnemoneJar = $line.Substring('anemone.jar='.Length)
}
& (Join-Path $PSScriptRoot 'build.ps1') -AnemoneJar $AnemoneJar
$projectRoot = Split-Path $PSScriptRoot -Parent
$classpath = (Join-Path $PSScriptRoot 'build/classes') + [IO.Path]::PathSeparator + $AnemoneJar
& java '-Xms16m' '-Xmx128m' '-Djava.awt.headless=true' "-Dsmartstock.root=$projectRoot" "-Dsmartstock.port=$Port" -cp $classpath smartstock.Main
if ($LASTEXITCODE -ne 0) { throw 'Le Bridge a quitté avec une erreur' }
