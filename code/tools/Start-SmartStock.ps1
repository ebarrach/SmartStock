param(
    [Parameter(Mandatory=$true)][ValidateSet('Demo','Real')][string]$Mode,
    [ValidateRange(1024,65535)][int]$Port=18099,
    [Alias('DataPath')][string]$DataDirectory,
    [string]$JarPath,
    [string]$CatalogPath,
    [string]$Java='java',
    [string]$BindAddress='0.0.0.0',
    [string]$InterfaceAlias,
    [string]$Address
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
function ProjectPath([string]$value) {
    if([IO.Path]::IsPathRooted($value)){return [IO.Path]::GetFullPath($value)}
    return [IO.Path]::GetFullPath((Join-Path $root $value))
}
if(!$DataDirectory){$DataDirectory='code/data'}
if(!$JarPath){$JarPath='code/smartstock-agent/target/scala-2.13/smartstock-agent.jar'}
$data=ProjectPath $DataDirectory
$jar=(Resolve-Path -LiteralPath (ProjectPath $JarPath)).Path
if(Test-Path -LiteralPath $data -PathType Leaf){throw 'Un dossier de JSON est requis. Migrer explicitement les anciens fichiers monolithiques.'}
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Port $Port deja occupe. Aucun service arrete."}
New-Item -ItemType Directory -Path $data -Force | Out-Null
$meta=Join-Path $data 'metadata.json'
if(Test-Path -LiteralPath $meta){
    $metadata=Get-Content -Raw -LiteralPath $meta -Encoding UTF8 | ConvertFrom-Json
    if($Mode -eq 'Real' -and $metadata.dataset -eq 'demo'){throw 'Stockage marque Demo : conserver ce profil ou effectuer une migration explicite distincte.'}
}
$sha=[Security.Cryptography.SHA256]::Create()
$hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($data.ToLowerInvariant())))).Replace('-','')
$sha.Dispose()
$mutex=New-Object Threading.Mutex($false,('Local\SmartStock-'+$hash))
$held=$false
$names=@('SMARTSTOCK_HOME','SMARTSTOCK_PORT','SMARTSTOCK_BIND','SMARTSTOCK_DATA_DIR','SMARTSTOCK_DATA','SMARTSTOCK_DEMO_DATA','SMARTSTOCK_CATALOG')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $held=$mutex.WaitOne(0)
    if(!$held){throw 'Un lanceur utilise deja ce dossier.'}
    $env:SMARTSTOCK_HOME=$root
    $env:SMARTSTOCK_DATA_DIR=$data
    $env:SMARTSTOCK_BIND=$BindAddress
    $env:SMARTSTOCK_PORT=[string]$Port
    Remove-Item Env:SMARTSTOCK_DATA,Env:SMARTSTOCK_DEMO_DATA -ErrorAction SilentlyContinue
    if($CatalogPath){$env:SMARTSTOCK_CATALOG=(Resolve-Path -LiteralPath (ProjectPath $CatalogPath)).Path}
    else{$env:SMARTSTOCK_CATALOG=Join-Path $root $(if($Mode -eq 'Demo'){'code/smartstock-agent/config/demo-catalog.json'}else{'code/smartstock-agent/config/catalog.json'})}
    Write-Host "SmartStock $Mode | dossier absolu des donnees : $data"
    Write-Host 'Fichiers : products.json, batches.json, units.json, movements.json, settings.json, calibration.json, receipts.json, sensors.json, metadata.json'
    Write-Host 'Transaction : transaction.json (pendant ecriture/reprise), commit.json, store.lock'
    Write-Host "Catalogue de parametres : $env:SMARTSTOCK_CATALOG (aucune reinjection automatique)"
    Write-Host "Ecoute : $BindAddress`:$Port | PC : http://127.0.0.1:$Port/api/v1/health"
    & (Join-Path $PSScriptRoot 'Get-ConnectionCandidates.ps1') -Port $Port -InterfaceAlias $InterfaceAlias -Address $Address | Format-Table -AutoSize
    Write-Host 'Garder ce terminal ouvert. Ctrl+C pour arreter. Aucune fixture rechargee au redemarrage.'
    if($Mode -eq 'Demo'){& $Java -jar $jar --demo}else{& $Java -jar $jar}
    if($LASTEXITCODE -ne 0){throw "Agent termine avec code $LASTEXITCODE"}
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    if($held){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
