<#
.SYNOPSIS
    Runs pre-push.ps1 end to end on a throwaway repository, with a stand-in Gradle wrapper, build
    queue and patch check, and checks the order the gate builds in and where it stops.
.DESCRIPTION
    The repository holds this checkout's pre-push.ps1 and the scripts it loads, a one-patch
    catalog and a source file. Each case pushes a commit that changes that file, which the hook
    reads as a build change. The stand-ins write what a real build would leave in the gate's
    worktree (test results, the bundle, its SBOM) and log what they were asked, so the order of
    the builds, the queue slot and the release priority can be read back. Nothing is compiled.
.NOTES
    Copyright 2026 HushGram contributors. https://github.com/SysAdminDoc/HushGram
    GPL-3.0-only.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = [IO.Path]::GetFullPath($Root)
$scratch = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('hushgram-push-gate-' + [guid]::NewGuid().ToString('N'))))
$passed = 0
$version = '450.0.0.50.77'
$hookScripts = @('pre-push.ps1', 'common.ps1', 'patch-target.ps1', 'build-jobs.ps1')
$hookVariables = @('HUSHGRAM_SKIP_PRE_PUSH', 'HUSHGRAM_ALLOW_RELEASE', 'HUSHGRAM_FIXTURE_DIR', 'HUSHGRAM_DESKTOP_JAR',
    'HUSHGRAM_WORKDIR', 'HUSHGRAM_BUILD_WRAPPER', 'BUILD_QUEUE_SCRIPT', 'BUILD_QUEUE_PRIORITY', 'BUILD_QUEUE_TICKET',
    'HUSHGRAM_GATE_TEST_LOG', 'HUSHGRAM_GATE_TEST_FAIL', 'HUSHGRAM_GATE_TEST_STALE')
$saved = @{}
foreach ($name in $hookVariables) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process) }

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}

function Invoke-FixtureGit {
    # $args, not a parameter: git's own switches (-A, -c, -m) would bind to one.
    $gitArguments = $args
    $output = & git -C $script:repo @gitArguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "git $($gitArguments -join ' ') failed: $output" }
    return $output
}

function Write-FixtureFile([string]$Relative, [string]$Text) {
    $path = Join-Path $script:repo $Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
    [IO.File]::WriteAllText($path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-FixtureCommit([string]$Message) {
    Invoke-FixtureGit add -A | Out-Null
    Invoke-FixtureGit -c user.name=SysAdminDoc -c user.email=matt_parker@outlook.com commit -q -m $Message | Out-Null
    return "$(Invoke-FixtureGit rev-parse HEAD)".Trim()
}

function Clear-Log { Remove-Item -LiteralPath $script:log -ErrorAction SilentlyContinue }
function Read-Log { if (Test-Path -LiteralPath $script:log) { @(Get-Content -LiteralPath $script:log) } else { @() } }
function Get-GradleRuns { @(Read-Log | Where-Object { $_ -like 'gradle *' }) }

function Invoke-Push {
    <# The hook as git runs it, for the push of -Tip over -Base, with the stand-ins switched in. #>
    param([string]$Tip, [string]$Base, [hashtable]$Environment = @{})
    foreach ($name in $hookVariables) { Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue }
    $env:HUSHGRAM_BUILD_WRAPPER = $script:wrapper
    $env:BUILD_QUEUE_SCRIPT = $script:queue
    $env:HUSHGRAM_GATE_TEST_LOG = $script:log
    # Somewhere that isn't a folder, so a maintainer's own fixtures aren't read unless a case asks.
    $env:HUSHGRAM_FIXTURE_DIR = Join-Path $scratch 'no-fixtures'
    $env:HUSHGRAM_DESKTOP_JAR = Join-Path $scratch 'no-cli.jar'
    foreach ($name in $Environment.Keys) { Set-Item -LiteralPath "Env:\$name" -Value $Environment[$name] }
    Clear-Log
    $output = @("refs/heads/main $Tip refs/heads/main $Base" |
        & pwsh -NoProfile -File (Join-Path $script:repo 'scripts/pre-push.ps1') origin 'https://example.invalid/hushgram.git' 2>&1 |
        ForEach-Object { "$_" })
    $exit = $LASTEXITCODE
    foreach ($name in $hookVariables) { Remove-Item -LiteralPath "Env:\$name" -ErrorAction SilentlyContinue }
    return [pscustomobject]@{ Exit = $exit; Output = $output -join "`n" }
}

try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    $script:log = Join-Path $scratch 'calls.log'

    # The stand-in Gradle wrapper: logs the tasks, the queue priority and whether it ran inside a
    # slot, and writes what each task would leave. HUSHGRAM_GATE_TEST_FAIL names a task that fails;
    # HUSHGRAM_GATE_TEST_STALE has the catalog come out different from the committed one.
    $script:wrapper = Join-Path $scratch 'wrapper.ps1'
    Set-Content -LiteralPath $script:wrapper -Encoding UTF8 -Value @'
param([string]$ProjectDir, [string[]]$Tasks)
Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value ("gradle $($Tasks -join ' ') priority=$env:BUILD_QUEUE_PRIORITY " +
    "slot=$([bool]$env:BUILD_QUEUE_TICKET)")
function Write-Results([string]$Folder, [string]$Suite, [int]$Cases) {
    New-Item -ItemType Directory -Force -Path $Folder | Out-Null
    $body = (1..$Cases | ForEach-Object { "<testcase name=`"case$_`" classname=`"$Suite`"/>" }) -join ''
    Set-Content -LiteralPath (Join-Path $Folder "TEST-$Suite.xml") -Encoding UTF8 -Value (
        "<?xml version=`"1.0`"?><testsuite name=`"$Suite`" tests=`"$Cases`" failures=`"0`" errors=`"0`" skipped=`"0`">$body</testsuite>")
}
if ($Tasks -contains ':patches:generatePatchesList' -and $env:HUSHGRAM_GATE_TEST_STALE) {
    Add-Content -LiteralPath (Join-Path $ProjectDir 'patches-list.json') -Value ' '
}
if ($Tasks -contains ':extensions:instagram:testDebugUnitTest') {
    Write-Results (Join-Path $ProjectDir 'extensions/instagram/build/test-results/testDebugUnitTest') 'app.hushgram.RuntimeTest' 3
}
if ($Tasks -contains ':patches:test') {
    Write-Results (Join-Path $ProjectDir 'patches/build/test-results/test') 'app.morphe.PatchTest' 2
}
if ($Tasks -contains ':patches:buildAndroid') {
    $release = Join-Path $ProjectDir 'patches/build/release'
    New-Item -ItemType Directory -Force -Path $release | Out-Null
    Set-Content -LiteralPath (Join-Path $release 'patches-0.0.1.mpp') -Value 'bundle'
    Set-Content -LiteralPath (Join-Path $release 'patches-0.0.1.cdx.json') -Value '{}'
    Set-Content -LiteralPath (Join-Path $release 'bundle.sha256') -Value 'sum'
}
if ($env:HUSHGRAM_GATE_TEST_FAIL -and $Tasks -contains $env:HUSHGRAM_GATE_TEST_FAIL) { exit 1 }
exit 0
'@
    # The stand-in queue: one ticket at a time, logged.
    $script:queue = Join-Path $scratch 'queue.ps1'
    Set-Content -LiteralPath $script:queue -Encoding UTF8 -Value @'
param([switch]$Status, [string]$Label, [string]$Priority, [string]$Run)
function Enter-BuildQueue {
    param([string]$Label, [string]$Priority)
    Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value "queue enter $Label $Priority"
    $env:BUILD_QUEUE_TICKET = 'stand-in'
    [pscustomobject]@{ slot = 0; path = 'stand-in' }
}
function Exit-BuildQueue {
    param($Ticket)
    Add-Content -LiteralPath $env:HUSHGRAM_GATE_TEST_LOG -Value 'queue exit'
    Remove-Item Env:\BUILD_QUEUE_TICKET -ErrorAction SilentlyContinue
}
function Get-BuildQueueMask { param([int]$Slot) [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity }
'@

    $script:repo = Join-Path $scratch 'repo'
    New-Item -ItemType Directory -Path $script:repo | Out-Null
    Invoke-FixtureGit init -q -b main | Out-Null
    Invoke-FixtureGit config core.autocrlf false | Out-Null
    foreach ($name in $hookScripts) {
        Write-FixtureFile "scripts/$name" ([IO.File]::ReadAllText((Join-Path $Root "scripts/$name")))
    }
    Write-FixtureFile 'gradle.properties' "version = 0.0.1`n"
    Write-FixtureFile 'patches-list.json' (@{
        version = 'v0.0.1'
        patches = @(@{ name = 'Stand-in patch'; compatiblePackages = @{ 'com.instagram.android' = @($version) } })
    } | ConvertTo-Json -Depth 6)
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// first`n"
    $base = New-FixtureCommit 'base'
    Write-FixtureFile 'patches/src/main/kotlin/StandIn.kt' "// second`n"
    $tip = New-FixtureCommit 'change a patch'
    $short = $tip.Substring(0, 12)

    # Cheap checks first, in one build, then the patch tests and the bundle, all in one slot.
    $run = Invoke-Push -Tip $tip -Base $base
    $builds = Get-GradleRuns
    Assert-True ($run.Exit -eq 0) "The gate refused a clean change: $($run.Output)"
    Assert-True ($builds.Count -eq 2) "The gate ran $($builds.Count) builds, not two: $($builds -join '; ')"
    Assert-True ($builds[0] -like '*:patches:generatePatchesList*' -and $builds[0] -like '*:extensions:instagram:lint*' -and
        $builds[0] -like '*:extensions:shared:library:lint*' -and $builds[0] -like '*:extensions:instagram:testDebugUnitTest*' -and
        $builds[0] -like '*:extensions:instagram:verifyAndroidBoundaries*' -and $builds[0] -notlike '*:patches:test*' -and
        $builds[0] -notlike '*:patches:buildAndroid*') "The first build isn't the quick one: $($builds[0])"
    Assert-True ($builds[1] -like '*:patches:test*' -and $builds[1] -like '*:patches:buildAndroid*') `
        "The second build isn't the patch tests and the bundle: $($builds[1])"
    $queueLines = @(Read-Log | Where-Object { $_ -like 'queue *' })
    Assert-True (($queueLines -join ',') -eq "queue enter hushgram gate $short normal,queue exit" -and
        @($builds | Where-Object { $_ -like '*slot=True' }).Count -eq 2) `
        "The gate didn't build inside one everyday slot: $((Read-Log) -join '; ')"
    Assert-True ($run.Output -like '*HUSHGRAM_FIXTURE_DIR is not set*') "The gate didn't say it patched nothing: $($run.Output)"

    # A lint that fails stops the push before the quarter hour of patch tests.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_FAIL = ':extensions:instagram:lint' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 1 -and $run.Output -like '*the runtime tests, a lint or the catalog failed*') `
        "A failed quick build didn't stop the gate before the patch tests: $((Get-GradleRuns) -join '; ') $($run.Output)"
    Assert-True ((@(Read-Log | Where-Object { $_ -like 'queue *' }) -join ',') -eq "queue enter hushgram gate $short normal,queue exit") `
        'A refused gate did not give its slot back.'

    # So does a catalog that doesn't match the patches.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_STALE = '1' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 1 -and $run.Output -like '*patches-list.json is stale*') `
        "A stale catalog didn't stop the gate before the patch tests: $($run.Output)"

    # A failed patch test or bundle is still a refusal.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_GATE_TEST_FAIL = ':patches:test' }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 2 -and $run.Output -like '*the patch tests failed or the bundle did not build*') `
        "A failed patch test didn't stop the gate: $($run.Output)"

    # A declared build with no fixture stops the push before anything is built.
    $fixtureDir = Join-Path $scratch 'fixtures'
    New-Item -ItemType Directory -Path $fixtureDir | Out-Null
    $desktop = Join-Path $scratch 'morphe-desktop-stand-in.jar'
    Set-Content -LiteralPath $desktop -Value 'jar'
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_FIXTURE_DIR = $fixtureDir; HUSHGRAM_DESKTOP_JAR = $desktop }
    Assert-True ($run.Exit -ne 0 -and (Get-GradleRuns).Count -eq 0 -and $run.Output -like "*no fixture for the declared build $version*") `
        "A missing fixture didn't stop the gate before the build: $((Get-GradleRuns) -join '; ') $($run.Output)"

    # A push made for a release is first in line, and its builds know it.
    $run = Invoke-Push -Tip $tip -Base $base -Environment @{ HUSHGRAM_ALLOW_RELEASE = '1' }
    Assert-True ($run.Exit -eq 0 -and @(Get-GradleRuns | Where-Object { $_ -like '*priority=release slot=True' }).Count -eq 2 -and
        (Read-Log) -contains "queue enter hushgram gate $short release") `
        "A release push didn't take its slot at release priority: $((Read-Log) -join '; ')"

    Assert-True (@(Invoke-FixtureGit worktree list).Count -eq 1) 'A gate left its worktree behind.'
    . (Join-Path $PSScriptRoot 'script-wiring.ps1')
    Assert-True (Test-PushGateRunsSuite (Join-Path $Root 'scripts/pre-push.ps1') 'scripts/test-push-gate.ps1') `
        'The push gate does not run this suite when the hook changes.'
    Write-Host "[push-gate] $passed checks passed"
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], [EnvironmentVariableTarget]::Process) }
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $scratch.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe push gate fixture cleanup path.' }
    if (Test-Path -LiteralPath $scratch) {
        if (Test-Path -LiteralPath (Join-Path $scratch 'repo/.git')) { & git -C (Join-Path $scratch 'repo') worktree prune 2>$null | Out-Null }
        Remove-Item -LiteralPath $scratch -Recurse -Force
    }
}
