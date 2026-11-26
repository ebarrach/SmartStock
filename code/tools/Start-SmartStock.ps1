param(
    [Parameter(Mandatory=$true)][ValidateSet('Demo','Real')][string]$Mode,
    [ValidateRange(1024,65535)][int]$Port=18099,
    [Parameter(Mandatory=$true)][string]$DataPath,
    [Parameter(Mandatory=$true)][string]$JarPath,
    [string]$CatalogPath,
    [string]$Java='java'
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$agent=Join-Path $root 'code/smartstock-agent'
$jar=(Resolve-Path -LiteralPath $JarPath).Path
$data=[IO.Path]::GetFullPath($DataPath)
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Port $Port deja occupe. Aucun service arrete."}
if($Mode -eq 'Real' -and (Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object {$_.CommandLine -match '(?i)smartstock.*\.jar'})){
    throw 'Un service SmartStock existe deja : verifier et arreter manuellement le service utilisant les donnees reelles avant ce lancement. Aucun processus arrete automatiquement.'
}
$parent=Split-Path -Parent $data
New-Item -ItemType Directory -Path $parent -Force | Out-Null
# The JVM also owns a lifetime storage lock; this mutex prevents duplicate launchers.
$sha=[Security.Cryptography.SHA256]::Create()
$hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($data.ToLowerInvariant())))).Replace('-','')
$sha.Dispose()
$mutex=New-Object Threading.Mutex($false,('Local\SmartStock-'+$hash))
$held=$false
$names=@('SMARTSTOCK_PORT','SMARTSTOCK_BIND','SMARTSTOCK_DATA','SMARTSTOCK_DEMO_DATA','SMARTSTOCK_CATALOG')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $held=$mutex.WaitOne(0)
    if(!$held){throw 'Un lanceur utilise deja ce stockage.'}
    if(Test-Path -LiteralPath $data){
        $state=Get-Content -Raw -LiteralPath $data -Encoding UTF8 | ConvertFrom-Json
        if($Mode -eq 'Real' -and $state.dataset -eq 'demo'){throw 'Stockage Demo refuse en mode Real.'}
        if($Mode -eq 'Demo' -and $state.dataset -ne 'demo'){throw 'Choisir un nouveau stockage Demo : fichier existant non marque.'}
    }
    $env:SMARTSTOCK_BIND='0.0.0.0'
    $env:SMARTSTOCK_PORT=[string]$Port
    if($Mode -eq 'Demo'){$env:SMARTSTOCK_DEMO_DATA=$data}else{$env:SMARTSTOCK_DATA=$data}
    if($CatalogPath){$env:SMARTSTOCK_CATALOG=(Resolve-Path -LiteralPath $CatalogPath).Path}
    else{$env:SMARTSTOCK_CATALOG=Join-Path $agent $(if($Mode -eq 'Demo'){'config/demo-catalog.json'}else{'config/catalog.json'})}
    Write-Host "SmartStock $Mode | stockage : $data"
    Write-Host "Catalogue : $env:SMARTSTOCK_CATALOG"
    Write-Host "PC : http://127.0.0.1:$Port/api/v1/health"
    $physical=@(Get-NetAdapter -Physical | Where-Object {$_.Status -eq 'Up'} | Select-Object -ExpandProperty ifIndex)
    Get-NetIPAddress -AddressFamily IPv4 | Where-Object {
        $_.IPAddress -match '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' -and $_.AddressState -eq 'Preferred' -and $_.InterfaceIndex -in $physical
    } | ForEach-Object {Write-Host "Telephone ($($_.InterfaceAlias), meme reseau local) : http://$($_.IPAddress):$Port"}
    Write-Host 'Reseau local uniquement. Aucune regle pare-feu ni redirection routeur creee. Ctrl+C pour arreter.'
    Push-Location $agent
    try {
        if($Mode -eq 'Demo'){& $Java -jar $jar --demo}else{& $Java -jar $jar}
        if($LASTEXITCODE -ne 0){throw "Agent termine avec code $LASTEXITCODE"}
    } finally {Pop-Location}
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    if($held){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
