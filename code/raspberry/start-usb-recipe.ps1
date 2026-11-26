param(
    [int]$Port=18129,
    [string]$Java='java',
    [string]$Python='python',
    [Alias('DataPath')][string]$DataDirectory='code/data/recipes/usb',
    [switch]$InitializeExamples
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$directory=if([IO.Path]::IsPathRooted($DataDirectory)){[IO.Path]::GetFullPath($DataDirectory)}else{[IO.Path]::GetFullPath((Join-Path $root $DataDirectory))}
if($directory.TrimEnd('\','/') -eq (Join-Path $root 'code/data').TrimEnd('\','/')){throw 'La recette USB exige un dossier isole, jamais le stockage courant.'}
$jar=Join-Path $root 'code/smartstock-agent/target/scala-2.13/smartstock-agent.jar'
if(!(Test-Path -LiteralPath (Join-Path $directory 'commit.json'))){
    if(!$InitializeExamples){throw 'Recette absente : demander explicitement -InitializeExamples dans un dossier isole.'}
    $temp=Join-Path ([IO.Path]::GetTempPath()) ('smartstock-usb-'+[guid]::NewGuid().ToString())
    New-Item -ItemType Directory -Path $temp | Out-Null
    $seed=Join-Path $temp 'fixture.json'
    try {
        & $Python (Join-Path $PSScriptRoot 'usb_fixture.py') --output $seed
        if($LASTEXITCODE -ne 0){throw 'Fixture non creee.'}
        & $Java -cp $jar smartstock.inventory.StorageMigration $seed $directory --allow-examples
        if($LASTEXITCODE -ne 0){throw 'Import de recette refuse.'}
    } finally {
        if(Test-Path -LiteralPath $seed){Remove-Item -LiteralPath $seed}
        if(Test-Path -LiteralPath "$seed.lock"){Remove-Item -LiteralPath "$seed.lock"}
        Remove-Item -LiteralPath $temp
    }
} elseif($InitializeExamples){throw 'Recette deja presente : aucune reinitialisation.'}
$oldEnabled=$env:SMARTSTOCK_SCAN_ONLY_TEST_MODE
$oldAbsent=$env:SMARTSTOCK_TEST_SCALE_ABSENT
try {
    $env:SMARTSTOCK_SCAN_ONLY_TEST_MODE='true'
    $env:SMARTSTOCK_TEST_SCALE_ABSENT='true'
    & (Join-Path $root 'code/tools/Start-SmartStock.ps1') -Mode Demo -Port $Port -BindAddress '127.0.0.1' `
        -DataDirectory $directory -JarPath $jar -Java $Java
} finally {
    [Environment]::SetEnvironmentVariable('SMARTSTOCK_SCAN_ONLY_TEST_MODE',$oldEnabled,'Process')
    [Environment]::SetEnvironmentVariable('SMARTSTOCK_TEST_SCALE_ABSENT',$oldAbsent,'Process')
}
