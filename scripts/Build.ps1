param(
    [string]$GameDirectory = $env:PZ_GAME_DIR,
    [string]$ZombieBuddyJar = $env:ZOMBIEBUDDY_JAR,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path -Parent $PSScriptRoot
if (-not $GameDirectory) {
    $steam = (Get-ItemProperty -LiteralPath 'HKCU:\Software\Valve\Steam').SteamPath
    $libraries = @($steam)
    $vdfPath = Join-Path $steam 'steamapps/libraryfolders.vdf'
    if (Test-Path -LiteralPath $vdfPath) {
        $vdf = Get-Content -LiteralPath $vdfPath -Raw
        $libraries += [regex]::Matches($vdf, '"path"\s+"([^"]+)"') | ForEach-Object { $_.Groups[1].Value.Replace('\\', '\') }
    }
    foreach ($library in $libraries) {
        $candidate = Join-Path $library 'steamapps/common/ProjectZomboid'
        if (Test-Path -LiteralPath (Join-Path $candidate 'projectzomboid.jar')) { $GameDirectory = $candidate; break }
    }
}
if (-not $GameDirectory) { throw 'Pass -GameDirectory or set PZ_GAME_DIR to your installed Project Zomboid directory.' }
$gameJar = Join-Path $GameDirectory 'projectzomboid.jar'
if (-not $ZombieBuddyJar) { $ZombieBuddyJar = Join-Path $GameDirectory 'ZombieBuddy.jar' }
foreach ($dependency in @($gameJar, $ZombieBuddyJar)) {
    if (-not (Test-Path -LiteralPath $dependency -PathType Leaf)) { throw "Missing compile-only dependency: $dependency" }
}
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$jar = (Get-Command jar -ErrorAction Stop).Source
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$run = Join-Path $project "build/run-$stamp"
$classes = Join-Path $run 'classes'
$tests = Join-Path $run 'tests'
New-Item -ItemType Directory -Path $classes, $tests -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
function Write-ArgFile([string]$path, [string]$root) {
    $files = Get-ChildItem -LiteralPath $root -Filter '*.java' -File -Recurse | Sort-Object FullName
    $lines = $files | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
    [IO.File]::WriteAllLines($path, $lines, $utf8)
}
function Invoke-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $program" }
}
$dependencies = $gameJar + [IO.Path]::PathSeparator + $ZombieBuddyJar
$sourceArgs = Join-Path $run 'main-sources.txt'
Write-ArgFile $sourceArgs (Join-Path $project 'src/main/java')
Invoke-Checked $javac @('--release','17','-encoding','UTF-8','-Xlint:all','-Werror','-cp',$dependencies,'-d',$classes,"@$sourceArgs")
$passed = @()
if (-not $SkipTests) {
    $testArgs = Join-Path $run 'test-sources.txt'
    Write-ArgFile $testArgs (Join-Path $project 'src/test/java')
    $testCp = $classes + [IO.Path]::PathSeparator + $dependencies
    Invoke-Checked $javac @('--release','17','-encoding','UTF-8','-Xlint:all','-Werror','-cp',$testCp,'-d',$tests,"@$testArgs")
    $testCp = $tests + [IO.Path]::PathSeparator + $testCp
    Push-Location $project
    try {
        foreach ($test in @(
            'dev.poweredfreshness.core.CoreTests',
            'dev.poweredfreshness.runtime.AgeJournalTest',
            'dev.poweredfreshness.runtime.PowerTrackerTests',
            'dev.poweredfreshness.net.ProtocolTest',
            'dev.poweredfreshness.net.FairOutboxTest',
            'dev.poweredfreshness.net.NetworkPayloadTest',
            'dev.poweredfreshness.net.LuaBridgeTest',
            'dev.poweredfreshness.runtime.HooksTest',
            'dev.poweredfreshness.instrument.GameTransformerTest'
        )) {
            $testArguments = @('-Xverify:all','-cp',$testCp,$test)
            if ($test -eq 'dev.poweredfreshness.instrument.GameTransformerTest') { $testArguments += $gameJar }
            if ($test -eq 'dev.poweredfreshness.runtime.PowerTrackerTests') { $testArguments += '--game' }
            Invoke-Checked $java $testArguments
            $passed += $test
        }
        $agent = Join-Path $run 'test-agent.jar'
        $manifest = Join-Path $project 'src/test/java/dev/poweredfreshness/instrument/test-agent.mf'
        Invoke-Checked $jar @('--create','--file',$agent,'--manifest',$manifest,'-C',$tests,'.')
        Invoke-Checked $java @("-javaagent:$agent",'-Xverify:all','-cp',$testCp,'dev.poweredfreshness.instrument.InstrumentationTest')
        $passed += 'dev.poweredfreshness.instrument.InstrumentationTest'
        Invoke-Checked $java @("-javaagent:$agent",'-Xverify:all','-cp',$testCp,'dev.poweredfreshness.instrument.BootstrapTest')
        $passed += 'dev.poweredfreshness.instrument.BootstrapTest'
        $foodAgent = Join-Path $run 'food-test-agent.jar'
        $foodManifest = Join-Path $project 'src/test/java/dev/poweredfreshness/runtime/food-integration-agent.mf'
        Invoke-Checked $jar @('--create','--file',$foodAgent,'--manifest',$foodManifest,'-C',$tests,'.')
        $fixtureHome = Join-Path $run 'fixture-home'
        New-Item -ItemType Directory -Path $fixtureHome -Force | Out-Null
        Invoke-Checked $java @("-javaagent:$foodAgent",'-Xverify:all',"-Duser.home=$fixtureHome",'-cp',$testCp,'dev.poweredfreshness.runtime.FoodIntegrationTest')
        $passed += 'dev.poweredfreshness.runtime.FoodIntegrationTest'
    } finally { Pop-Location }
}
$stage = Join-Path $run 'package'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $project 'mod/PoweredFreshness') -Destination $stage -Recurse
# B42 scans these directories even when a Mod has no animation assets.
foreach ($versionDirectory in @('common','42')) {
    foreach ($assetDirectory in @('AnimSets','actiongroups')) {
        New-Item -ItemType Directory -Path (Join-Path $stage "PoweredFreshness/$versionDirectory/media/$assetDirectory") -Force | Out-Null
    }
}
$modJarDir = Join-Path $stage 'PoweredFreshness/42/media/java'
New-Item -ItemType Directory -Path $modJarDir -Force | Out-Null
$modJar = Join-Path $modJarDir 'PoweredFreshness.jar'
Invoke-Checked $jar @('--create','--file',$modJar,'-C',$classes,'.')
foreach ($doc in @('README.md','LICENSE','THIRD_PARTY_NOTICES.md')) {
    $file = Join-Path $project $doc
    if (Test-Path -LiteralPath $file) { Copy-Item -LiteralPath $file -Destination $stage }
}
$dist = Join-Path $project 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$zip = Join-Path $dist "PoweredFreshness-0.1.0-$stamp.zip"
Copy-Item -LiteralPath (Join-Path $project 'docs') -Destination $stage -Recurse
$release = [ordered]@{
    version='0.1.0'; gameVersion='42.20.4'; zombieBuddyVersion='2.3.2'
    modId='PoweredFreshness'; tests=$passed; automatedTestsRun=(-not $SkipTests.IsPresent)
    modJarSha256=(Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash
    gameJarSha256=(Get-FileHash -LiteralPath $gameJar -Algorithm SHA256).Hash
    zombieBuddySha256=(Get-FileHash -LiteralPath $ZombieBuddyJar -Algorithm SHA256).Hash
    realMultiplayerPlaytestPassed=$false
}
[IO.File]::WriteAllText((Join-Path $stage 'RELEASE.json'), ($release | ConvertTo-Json -Depth 5), $utf8)
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip
$zipHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash
[IO.File]::WriteAllText(($zip + '.sha256'), ($zipHash + '  ' + [IO.Path]::GetFileName($zip) + [Environment]::NewLine), $utf8)
$result = [ordered]@{
    version='0.1.0'; gameVersion='42.20.4'; createdUtc=[DateTime]::UtcNow.ToString('o')
    zip=$zip; zipSha256=$zipHash
    modJarSha256=(Get-FileHash -LiteralPath $modJar -Algorithm SHA256).Hash
    gameJarSha256=(Get-FileHash -LiteralPath $gameJar -Algorithm SHA256).Hash
    zombieBuddySha256=(Get-FileHash -LiteralPath $ZombieBuddyJar -Algorithm SHA256).Hash
    tests=$passed; classes=$classes; testClasses=$tests; package=$stage
}
[IO.File]::WriteAllText((Join-Path $run 'result.json'), ($result | ConvertTo-Json -Depth 5), $utf8)
[IO.File]::WriteAllText((Join-Path $project 'build/latest-result.json'), ($result | ConvertTo-Json -Depth 5), $utf8)
$result | ConvertTo-Json -Depth 5
