param(
    [Parameter(Mandatory=$true)][string]$Keystore,
    [Parameter(Mandatory=$true)][string]$Alias,
    [string]$LocalHttpHost,
    [ValidateSet('Apk','Bundle','Both')][string]$Target='Apk'
)
$ErrorActionPreference='Stop'
$file=(Resolve-Path -LiteralPath $Keystore).Path
if([IO.Path]::GetFileName($file) -eq 'debug.keystore' -or $Alias -eq 'androiddebugkey'){throw 'Cle debug interdite pour la release.'}
$android=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../mobile-app/android'))
$store=Read-Host 'Mot de passe du keystore (saisie locale masquee)' -AsSecureString
$key=Read-Host 'Mot de passe de la cle (saisie locale masquee)' -AsSecureString
$names=@('SMARTSTOCK_RELEASE_STORE_FILE','SMARTSTOCK_RELEASE_STORE_PASSWORD','SMARTSTOCK_RELEASE_KEY_ALIAS','SMARTSTOCK_RELEASE_KEY_PASSWORD')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
function Plain([Security.SecureString]$secret){
    $ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)}finally{[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)}
}
try {
    $env:SMARTSTOCK_RELEASE_STORE_FILE=$file
    $env:SMARTSTOCK_RELEASE_KEY_ALIAS=$Alias
    $env:SMARTSTOCK_RELEASE_STORE_PASSWORD=Plain $store
    $env:SMARTSTOCK_RELEASE_KEY_PASSWORD=Plain $key
    $tasks=switch($Target){'Apk'{@(':app:assembleRelease')};'Bundle'{@(':app:bundleRelease')};'Both'{@(':app:assembleRelease',':app:bundleRelease')}}
    $argsList=@($tasks)+@('--no-daemon','--max-workers=2','--console=plain')
    if($LocalHttpHost){Write-Host 'LocalHttpHost est obsolete : IPv4 privees configurables dans les profils, sans reconstruction.'}
    Push-Location $android
    try {& ./gradlew.bat @argsList; if($LASTEXITCODE -ne 0){throw 'Compilation release echouee.'}}
    finally {Pop-Location}
    if($Target -ne 'Bundle'){Write-Host "APK signe : $(Join-Path $android 'app/build/outputs/apk/release/app-release.apk')"}
    if($Target -ne 'Apk'){
        Write-Host "AAB signe : $(Join-Path $android 'app/build/outputs/bundle/release/app-release.aab')"
        Write-Host 'HTTP des appels SmartStock limite aux IPv4 privees/loopback ; HTTPS conserve la validation standard.'
    }
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    $store.Dispose();$key.Dispose()
}
