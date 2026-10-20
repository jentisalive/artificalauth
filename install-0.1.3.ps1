$ErrorActionPreference='Stop'
$root='D:\ArtificalSMP\AuthPlugin'
$plugins='D:\ArtificalSMP\Velocity\plugins'
$old=Join-Path $plugins 'ArtificalAuth-0.1.2-beta.jar'
$new=Join-Path $plugins 'ArtificalAuth-0.1.3-beta.jar'
$source=Join-Path $root 'dist\ArtificalAuth-0.1.3-beta.jar'
if(Test-Path -LiteralPath $new){throw 'New version is already installed; inspect before replacing.'}
try{$stream=[IO.File]::Open($old,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None);$stream.Dispose()}
catch{throw 'Velocity still holds its plugin JAR. Stop the proxy before installing.'}
$backup=Join-Path $root ('backups\install-0.1.3-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
if(-not [IO.Path]::GetFullPath($backup).StartsWith('D:\ArtificalSMP\AuthPlugin\backups\')){throw 'Invalid backup path'}
New-Item -ItemType Directory -Path $backup | Out-Null
Copy-Item -LiteralPath (Join-Path $plugins 'artificalauth\config.yml') -Destination (Join-Path $backup 'config.yml')
if(Test-Path -LiteralPath (Join-Path $plugins 'artificalauth\accounts.json')){Copy-Item -LiteralPath (Join-Path $plugins 'artificalauth\accounts.json') -Destination (Join-Path $backup 'accounts.json')}
$archived=Join-Path $backup 'ArtificalAuth-0.1.2-beta.jar'
Move-Item -LiteralPath $old -Destination $archived
try{
 Copy-Item -LiteralPath $source -Destination $new
 if((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $new).Hash){throw 'JAR hash mismatch'}
}catch{
 if(Test-Path -LiteralPath $new){Move-Item -LiteralPath $new -Destination (Join-Path $backup 'failed-new.jar')}
 Move-Item -LiteralPath $archived -Destination $old
 throw
}
Write-Output 'Installed ArtificalAuth 0.1.3-beta. Config and accounts unchanged.'
Write-Output ('Backup: '+$backup)