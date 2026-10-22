$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$sandbox=Join-Path $root ('build\install-test-'+[guid]::NewGuid().ToString('N'))
$plugins=Join-Path $sandbox 'plugins'
New-Item -ItemType Directory -Force (Join-Path $plugins 'artificalauth') | Out-Null
Copy-Item (Join-Path $root 'dist\ArtificalAuth-0.1.3-beta.jar') $plugins
$utf8=[Text.UTF8Encoding]::new($false)
$config=Join-Path $plugins 'artificalauth\config.yml'
$accounts=Join-Path $plugins 'artificalauth\accounts.json'
[IO.File]::WriteAllText($config,"# Synthetic installer test only`nenabled: false`nbot:`n  token: LOCAL_TEST_TOKEN`n",$utf8)
[IO.File]::WriteAllText($accounts,'{"schema":1,"accounts":{}}',$utf8)
$beforeConfig=(Get-FileHash $config).Hash
$beforeAccounts=(Get-FileHash $accounts).Hash
& (Join-Path $root 'install-0.1.4.ps1') -Plugins $plugins -Apply
if(Test-Path (Join-Path $plugins 'ArtificalAuth-0.1.3-beta.jar')){throw 'Previous jar still enabled'}
if((Get-FileHash (Join-Path $plugins 'ArtificalAuth-0.1.4-beta.jar')).Hash -ne (Get-FileHash (Join-Path $root 'dist\ArtificalAuth-0.1.4-beta.jar')).Hash){throw 'New jar differs'}
if((Get-FileHash $config).Hash -ne $beforeConfig -or (Get-FileHash $accounts).Hash -ne $beforeAccounts){throw 'Installer changed config or accounts'}
Write-Output 'PASS: isolated 0.1.3 -> 0.1.4 installation, unchanged config/accounts, matching new jar, backup of previous version.'
