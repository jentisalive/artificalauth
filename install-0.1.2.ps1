$ErrorActionPreference = 'Stop'
$pluginRoot = 'D:\ArtificalSMP\Velocity\plugins'
$sourceJar = Join-Path $PSScriptRoot 'dist\ArtificalAuth-0.1.2-beta.jar'
$oldJar = Join-Path $pluginRoot 'ArtificalAuth-0.1.1-beta.jar'
$newJar = Join-Path $pluginRoot 'ArtificalAuth-0.1.2-beta.jar'
if (Test-Path -LiteralPath $newJar) { Write-Output 'ArtificalAuth 0.1.2 is already installed.'; exit 0 }
try { $lockCheck = [IO.File]::Open($oldJar,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None); $lockCheck.Dispose() }
catch { throw 'Stop Velocity with end before installing. The running proxy locks its plugin JAR.' }
$backupDir = Join-Path $PSScriptRoot ('backups\install-0.1.2-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
if (-not [IO.Path]::GetFullPath($backupDir).StartsWith('D:\ArtificalSMP\AuthPlugin\backups\')) {throw 'Invalid backup path'}
New-Item -ItemType Directory -Path $backupDir | Out-Null
$configPath = Join-Path $pluginRoot 'artificalauth\config.yml'
Copy-Item -LiteralPath $configPath -Destination (Join-Path $backupDir 'config.yml')
Copy-Item -LiteralPath $oldJar -Destination (Join-Path $backupDir 'original.jar')
$accountPath = Join-Path $pluginRoot 'artificalauth\accounts.json'
if(Test-Path -LiteralPath $accountPath){Copy-Item -LiteralPath $accountPath -Destination (Join-Path $backupDir 'accounts.json')}
$text=[IO.File]::ReadAllText($configPath)
if($text -notmatch '(?m)^  reload-subcommand:'){$text=[regex]::Replace($text,'(?m)(^  unlock-subcommand:.*$)','$1' + "`n" + '  reload-subcommand: "reload" # added | 17.10.2026 | ArtificalAuth 0.1.2')}
$extra=@()
if($text -notmatch '(?m)^  admin-reloaded:'){$extra+='  admin-reloaded: "<green>Конфиг ArtificalAuth перезагружен.</green>" # added | 17.10.2026 | ArtificalAuth 0.1.2'}
if($text -notmatch '(?m)^  admin-reload-failed:'){$extra+='  admin-reload-failed: "<red>Конфиг не применён. Сохранены предыдущие настройки; проверь консоль.</red>" # added | 17.10.2026 | ArtificalAuth 0.1.2'}
if($extra.Count -gt 0){$text=[regex]::Replace($text,'(?m)^messages:[ \t]*\r?$',('messages:' + "`n" + ($extra -join "`n")))}
$text=$text.Replace('  admin-usage: "<gray>Команда: {command} {unlock} <ник></gray>"','  admin-usage: "<gray>Команды: {command} {unlock} <ник> / {command} {reload}</gray>" # from unlock only | 17.10.2026 | ArtificalAuth 0.1.2')
$text='# Обновлено: 17.10.2026 | ArtificalAuth 0.1.2-beta | добавлена перезагрузка конфига.' + "`n" + $text
$archived = Join-Path $backupDir 'replaced-ArtificalAuth-0.1.1-beta.jar'
Move-Item -LiteralPath $oldJar -Destination $archived
try {
    [IO.File]::WriteAllText($configPath,$text,[Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath $sourceJar -Destination $newJar
    if((Get-FileHash -LiteralPath $sourceJar).Hash -ne (Get-FileHash -LiteralPath $newJar).Hash){throw 'JAR hash mismatch'}
} catch {
    if(Test-Path -LiteralPath $newJar){Move-Item -LiteralPath $newJar -Destination (Join-Path $backupDir 'failed-new.jar')}
    Copy-Item -LiteralPath (Join-Path $backupDir 'config.yml') -Destination $configPath -Force
    Move-Item -LiteralPath $archived -Destination $oldJar
    throw
}
Write-Output 'Installed ArtificalAuth 0.1.2-beta. Start Velocity. Config reload: authadmin reload'
Write-Output ('Backup: ' + $backupDir)