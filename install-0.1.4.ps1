param(
    [switch]$Apply,
    [string]$Plugins=(Join-Path (Split-Path $PSScriptRoot -Parent) 'Velocity\plugins')
)
$ErrorActionPreference='Stop'
$source=Join-Path $PSScriptRoot 'dist\ArtificalAuth-0.1.4-beta.jar'
$new=Join-Path $Plugins 'ArtificalAuth-0.1.4-beta.jar'
if(-not $Apply){
    Write-Output 'Plan: stop Velocity, back up the current ArtificalAuth jar/config/accounts, replace only the jar with 0.1.4-beta.'
    Write-Output 'Config and accounts are not rewritten. New Bedrock settings are merged in memory. Run with -Apply to install.'
    exit 0
}
if(-not(Test-Path -LiteralPath $source)){throw 'Build ArtificalAuth 0.1.4-beta first.'}
if(Test-Path -LiteralPath $new){throw '0.1.4-beta already exists; inspect before replacing.'}
$old=@(Get-ChildItem -LiteralPath $Plugins -Filter 'ArtificalAuth-*.jar' -File)
if($old.Count -ne 1){throw 'Expected exactly one installed ArtificalAuth jar. Inspect plugin versions before updating.'}
# JVMs often use relative jar paths: conservatively refuse while a Minecraft Java process is running.
if(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {$_.CommandLine -match 'server\.jar|velocity|purpur'}){
    throw 'Stop Minecraft Java processes before replacing the plugin. Do not use hot reload.'
}
try{$stream=[IO.File]::Open($old[0].FullName,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$stream.Dispose()}
catch{throw 'Installed jar is locked; stop Velocity before installing.'}
$backup=Join-Path $PSScriptRoot ('backups\install-0.1.4-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backup | Out-Null
foreach($file in @('config.yml','accounts.json')){
    $path=Join-Path $Plugins "artificalauth\$file"
    if(Test-Path -LiteralPath $path){Copy-Item -LiteralPath $path -Destination (Join-Path $backup $file)}
}
$archived=Join-Path $backup $old[0].Name
Move-Item -LiteralPath $old[0].FullName -Destination $archived
try{
    Copy-Item -LiteralPath $source -Destination $new
    if((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $new).Hash){throw 'Installed jar hash mismatch'}
}catch{
    if(Test-Path -LiteralPath $new){Move-Item -LiteralPath $new -Destination (Join-Path $backup 'failed-new.jar')}
    Move-Item -LiteralPath $archived -Destination $old[0].FullName
    throw
}
Write-Output 'ArtificalAuth 0.1.4-beta installed. Config and account files unchanged. Start Velocity normally.'
Write-Output "Backup: $backup"
