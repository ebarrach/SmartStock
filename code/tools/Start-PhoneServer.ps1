param(
    [ValidateSet('Demo','Real')][string]$Mode='Demo',
    [ValidateRange(1024,65535)][int]$Port=18119,
    [string]$DataDirectory='code/data',
    [string]$JarPath='code/smartstock-agent/target/scala-2.13/smartstock-agent.jar',
    [string]$Java='java'
)
$ErrorActionPreference='Stop'
$Host.UI.RawUI.WindowTitle="SmartStock - Serveur PC $Port - LAISSER OUVERT"
& (Join-Path $PSScriptRoot 'Start-SmartStock.ps1') -Mode $Mode -Port $Port `
    -DataDirectory $DataDirectory -JarPath $JarPath -Java $Java
