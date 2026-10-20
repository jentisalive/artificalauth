param(
    [string]$JavaHome = 'Z:\PrismLauncher\java\java-runtime-epsilon',
    [string]$VelocityJar = 'D:\ArtificalSMP\Velocity\server.jar',
    [string]$SnakeYamlJar = 'D:\ArtificalSMP\Main\libraries\org\yaml\snakeyaml\2.2\snakeyaml-2.2.jar'
)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$classes = Join-Path $project 'build\classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$classpath = "$VelocityJar;$(Join-Path $project 'lib\limboapi.jar');$SnakeYamlJar"
$sources = @(Get-ChildItem (Join-Path $project 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') --release 21 -encoding UTF-8 -proc:none -cp $classpath -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
Copy-Item (Join-Path $project 'src\main\resources\*') $classes -Recurse -Force
Push-Location $classes
try {
    & (Join-Path $JavaHome 'bin\jar.exe') xf $SnakeYamlJar org/yaml/snakeyaml
    if ($LASTEXITCODE -ne 0) { throw 'Dependency packaging failed' }
} finally { Pop-Location }
& (Join-Path $JavaHome 'bin\jar.exe') --create --file (Join-Path $project 'dist\ArtificalAuth-0.1.1-beta.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Jar packaging failed' }
Copy-Item (Join-Path $project 'lib\limboapi.jar') (Join-Path $project 'dist\limboapi-1.1.27-SNAPSHOT.jar') -Force
Copy-Item (Join-Path $project 'src\main\resources\config.yml') (Join-Path $project 'dist\config.yml') -Force
$tests = @(Get-ChildItem (Join-Path $project 'src\test\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
if ($tests.Count -gt 0) {
    & (Join-Path $JavaHome 'bin\javac.exe') --release 21 -encoding UTF-8 -proc:none -cp "$classes;$classpath" -d (Join-Path $project 'build\tests') @tests
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & (Join-Path $JavaHome 'bin\java.exe') -ea -cp "$(Join-Path $project 'build\tests');$classes;$classpath" ru.artificalsmp.auth.AuthTests $project
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}
Get-FileHash (Join-Path $project 'dist\*.jar') -Algorithm SHA256
