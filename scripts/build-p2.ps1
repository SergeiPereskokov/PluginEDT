#Requires -Version 5.1
<#
.SYNOPSIS
  Собирает p2-архив build.zip плагина storage для EDT «Установить новое ПО».

.DESCRIPTION
  Корневая ошибка fork.1: `jar cf` (BellSoft JDK) писал дефолтный MANIFEST
  (~58 байт, без OSGi-заголовков). Equinox не поднимал бандл, меню не было.

  Этот скрипт ВСЕГДА пакует plugin jar через `jar cfm` с исходным OSGi MANIFEST
  (qualifier подставляется). После упаковки - hard fail, если нет Bundle-SymbolicName.

  p2 metadata: шаблоны scripts/p2-templates (структура как у рабочего upstream/fork.1
  content.xml). FeaturesAndBundlesPublisher в продукте EDT нет.

.PARAMETER Qualifier
  OSGi qualifier. По умолчанию локальная дата/время yyyyMMddHHmm.

.PARAMETER OutDir
  Каталог для build.zip. По умолчанию <repo>/out
#>
[CmdletBinding()]
param(
    [string] $Qualifier,
    [string] $OutDir,
    [string] $JavaHome,
    [string] $PoolDir = "$env:USERPROFILE\.p2\pool\plugins"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-RepoRoot {
    $scripts = Split-Path -Parent $PSCommandPath
    return Split-Path -Parent $scripts
}

function Resolve-JavaHome {
    param([string] $Hint)
    $candidates = @()
    if ($Hint) { $candidates += $Hint }
    # EDT 2026.2 API jars = class file 69 (Java 25). javac 17 их не читает.
    # --release 17 тоже нельзя: компилятор тогда отвергает classpath 69.
    $candidates += @(
        'C:\Program Files\BellSoft\LibericaJDK-25-Full',
        'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot',
        'C:\Program Files\Axiom\AxiomJDK-Pro-17-Full'
    )
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe'))) {
            return (Resolve-Path $c).Path
        }
    }
    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) {
        return (Resolve-Path (Join-Path (Split-Path $javacCmd.Source) '..')).Path
    }
    throw 'JDK не найден (нужен javac). Задай -JavaHome или JAVA_HOME.'
}

function Resolve-PoolJar {
    param([string] $Pool, [string] $BundleId)
    $glob = Join-Path $Pool ($BundleId + '_*.jar')
    $rx = '^' + [regex]::Escape($BundleId) + '_\d'
    $cands = @(Get-ChildItem -Path $glob -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '\.source_' -and $_.Name -match $rx })
    if ($cands.Count -eq 0) {
        throw "Нет jar в pool: $BundleId (`$Pool=$Pool)"
    }
    return ($cands | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

function Get-ManifestText {
    param([string] $JarPath)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $e = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' } | Select-Object -First 1
        if (-not $e) { throw "В $JarPath нет META-INF/MANIFEST.MF" }
        $sr = New-Object IO.StreamReader($e.Open(), [Text.Encoding]::UTF8)
        try { return $sr.ReadToEnd() } finally { $sr.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Assert-OsgiPluginJar {
    param([string] $JarPath, [string] $ExpectedVersion)
    $mf = Get-ManifestText $JarPath
    $byteLen = [Text.Encoding]::UTF8.GetByteCount($mf)
    if ($byteLen -lt 400) {
        throw "MANIFEST слишком короткий ($byteLen байт) - похоже на голый jar cf. Файл: $JarPath"
    }
    if ($mf -notmatch 'Bundle-SymbolicName:\s*dev\.zigr\.dt\.team\.ui\.storage') {
        throw "Нет Bundle-SymbolicName в $JarPath. Фрагмент MANIFEST:`n$mf"
    }
    if ($mf -notmatch 'Bundle-ManifestVersion:\s*2') {
        throw "Нет Bundle-ManifestVersion: 2 в $JarPath"
    }
    if ($mf -notmatch 'Bundle-Activator:\s*dev\.zigr\.dt\.team\.ui\.storage\.StorageUiPlugin') {
        throw "Нет Bundle-Activator в $JarPath"
    }
    if ($mf -notmatch [regex]::Escape("Bundle-Version: $ExpectedVersion")) {
        throw "Bundle-Version != $ExpectedVersion в $JarPath`n$mf"
    }
    if ($mf -notmatch 'Require-Bundle:') {
        throw "Нет Require-Bundle в $JarPath"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName -replace '\\', '/' })
        foreach ($need in @(
                'plugin.xml',
                'icons/lock.png',
                'dev/zigr/dt/team/ui/storage/ExportHandler.class',
                'dev/zigr/dt/team/ui/storage/StorageUiPlugin.class',
                'dev/zigr/dt/team/ui/storage/SettingsHandler.class'
            )) {
            if ($names -notcontains $need) { throw "В plugin jar нет $need" }
        }
    }
    finally { $zip.Dispose() }
}

function New-Utf8NoBomFile {
    param([string] $Path, [string] $Content)
    $enc = New-Object Text.UTF8Encoding $false
    [IO.File]::WriteAllText($Path, $Content, $enc)
}

function New-ZipForwardSlash {
    param([string] $SourceDir, [string] $ZipPath)
    if (Test-Path $ZipPath) { Remove-Item $ZipPath -Force }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $srcRoot = (Resolve-Path $SourceDir).Path.TrimEnd('\', '/')
    $fs = [IO.File]::Open($ZipPath, [IO.FileMode]::Create)
    $zip = New-Object IO.Compression.ZipArchive($fs, [IO.Compression.ZipArchiveMode]::Create)
    try {
        Get-ChildItem -LiteralPath $srcRoot -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($srcRoot.Length).TrimStart('\', '/')
            $entryName = $rel.Replace('\', '/')
            [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal)
        }
    }
    finally {
        $zip.Dispose()
        $fs.Dispose()
    }
}

$repo = Get-RepoRoot
$pluginDir = Join-Path $repo 'dev.zigr.dt.team.ui.storage'
$featureSrc = Join-Path $repo 'dev.zigr.dt.team.ui.storage.feature'
$templateDir = Join-Path $repo 'scripts\p2-templates'
if (-not $OutDir) { $OutDir = Join-Path $repo 'out' }
if (-not $Qualifier) { $Qualifier = Get-Date -Format 'yyyyMMddHHmm' }
$version = "0.4.0.$Qualifier"
$jh = Resolve-JavaHome -Hint $JavaHome
$javac = Join-Path $jh 'bin\javac.exe'
$jarExe = Join-Path $jh 'bin\jar.exe'
if (-not (Test-Path $PoolDir)) { throw "p2 pool не найден: $PoolDir" }

Write-Host "repo      $repo"
Write-Host "version   $version"
Write-Host "JAVA_HOME $jh"
Write-Host "pool      $PoolDir"

$bundleIds = @(
    'com._1c.g5.v8.dt.platform.services.core',
    'com._1c.g5.v8.dt.platform.services.model',
    'com._1c.g5.v8.dt.core',
    'com._1c.g5.v8.dt.team',
    'com._1c.g5.v8.dt.team.git.infobases',
    'com._1c.g5.v8.dt.export',
    'com._1c.g5.v8.dt.common',
    'com._1c.g5.v8.bm.core',
    'com._1c.g5.v8.activitytracking.core',
    'com._1c.g5.wiring',
    'com._1c.g5.v8.dt.platform',
    'com._1c.g5.v8.dt.import',
    'com._1c.g5.v8.dt.metadata',
    'com._1c.g5.v8.dt.compare',
    'org.eclipse.core.runtime',
    'org.eclipse.core.resources',
    'org.eclipse.core.jobs',
    'org.eclipse.core.commands',
    'org.eclipse.core.contenttype',
    'org.eclipse.equinox.common',
    'org.eclipse.equinox.registry',
    'org.eclipse.equinox.preferences',
    'org.eclipse.equinox.security',
    'org.eclipse.osgi',
    'org.eclipse.ui',
    'org.eclipse.ui.workbench',
    'org.eclipse.jface',
    'org.eclipse.swt',
    'org.eclipse.swt.win32.win32.x86_64',
    'org.eclipse.jgit',
    'org.eclipse.emf.ecore',
    'org.eclipse.emf.common',
    'org.eclipse.xtext',
    'com.google.inject',
    'com.google.guava',
    'org.osgi.service.prefs',
    'org.eclipse.jdt.annotation',
    'org.eclipse.compare.core'
)

$cpList = foreach ($id in $bundleIds) { Resolve-PoolJar -Pool $PoolDir -BundleId $id }
$cp = [string]::Join(';', $cpList)

$work = Join-Path $OutDir ('_build-' + $version)
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
$classes = Join-Path $work 'classes'
$pluginStage = Join-Path $work 'plugin-stage'
$featStage = Join-Path $work 'feature-stage'
$repoStage = Join-Path $work 'p2'
$metaWork = Join-Path $work 'meta'
New-Item -ItemType Directory -Force -Path $classes, $pluginStage, $featStage, $metaWork | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $repoStage 'plugins'), (Join-Path $repoStage 'features') | Out-Null

$srcRoot = Join-Path $pluginDir 'src'
$javaFiles = @(Get-ChildItem -Path $srcRoot -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
if ($javaFiles.Count -eq 0) { throw "Нет .java в $srcRoot" }

Write-Host "javac $($javaFiles.Count) files"
# EDT plugins = bytecode 69. Не использовать --release 17.
$javacArgs = @('-encoding', 'UTF-8', '-cp', $cp, '-d', $classes) + $javaFiles
& $javac @javacArgs
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

# Stage plugin WITHOUT META-INF/MANIFEST.MF - иначе jar cfm смешает с дефолтным.
Copy-Item -Path (Join-Path $classes '*') -Destination $pluginStage -Recurse -Force
Copy-Item -Path (Join-Path $pluginDir 'plugin.xml') -Destination (Join-Path $pluginStage 'plugin.xml') -Force
$iconsSrc = Join-Path $pluginDir 'icons'
if (Test-Path $iconsSrc) {
    Copy-Item -Path $iconsSrc -Destination (Join-Path $pluginStage 'icons') -Recurse -Force
}

$srcMf = Get-Content -LiteralPath (Join-Path $pluginDir 'META-INF\MANIFEST.MF') -Raw -Encoding UTF8
if ($srcMf -notmatch 'Bundle-SymbolicName:\s*dev\.zigr\.dt\.team\.ui\.storage') {
    throw 'Исходный META-INF/MANIFEST.MF без Bundle-SymbolicName - сборка бессмысленна.'
}
$builtMf = $srcMf -replace 'Bundle-Version:\s*0\.4\.0\.qualifier', "Bundle-Version: $version"
if ($builtMf -eq $srcMf -and $srcMf -notmatch [regex]::Escape("Bundle-Version: $version")) {
    throw "Не удалось подставить Bundle-Version $version (ожидался 0.4.0.qualifier)"
}
# JAR spec: MANIFEST заканчивается пустой строкой, CRLF.
$builtMf = $builtMf.TrimEnd("`r", "`n") + "`r`n`r`n"
$mfFile = Join-Path $work 'MANIFEST.MF'
New-Utf8NoBomFile -Path $mfFile -Content $builtMf

$pluginJarName = "dev.zigr.dt.team.ui.storage_$version.jar"
$pluginJar = Join-Path $repoStage "plugins\$pluginJarName"

Write-Host "jar cfm (OSGi MANIFEST) -> $pluginJarName"
# НИКОГДА `jar cf` для plugin: получится Created-By-only MANIFEST (BellSoft/любой JDK).
Push-Location $pluginStage
try {
    & $jarExe cfm $pluginJar $mfFile .
    if ($LASTEXITCODE -ne 0) { throw "jar cfm plugin failed: $LASTEXITCODE" }
}
finally { Pop-Location }

Assert-OsgiPluginJar -JarPath $pluginJar -ExpectedVersion $version
Write-Host "OK plugin MANIFEST OSGi, bytes=$([Text.Encoding]::UTF8.GetByteCount((Get-ManifestText $pluginJar)))"

$featXmlSrc = Get-Content -LiteralPath (Join-Path $featureSrc 'feature.xml') -Raw -Encoding UTF8
$featXml = $featXmlSrc.Replace('version="0.4.0.qualifier"', "version=`"$version`"")
$featXml = $featXml.Replace('version="0.0.0"', "version=`"$version`"")
New-Utf8NoBomFile -Path (Join-Path $featStage 'feature.xml') -Content $featXml

$featureJarName = "dev.zigr.dt.team.ui.storage.feature_$version.jar"
$featureJar = Join-Path $repoStage "features\$featureJarName"
Push-Location $featStage
try {
    # feature jar - не OSGi-бандл; `jar cf` здесь допустим (как у upstream: без MANIFEST).
    & $jarExe cf $featureJar feature.xml
    if ($LASTEXITCODE -ne 0) { throw "jar cf feature failed: $LASTEXITCODE" }
}
finally { Pop-Location }

$pluginSize = (Get-Item $pluginJar).Length
$featureSize = (Get-Item $featureJar).Length
$ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$repoName = "PluginEDT fork $version"

$contentTemplate = Join-Path $templateDir 'content.xml'
if (-not (Test-Path $contentTemplate)) { throw "Нет шаблона $contentTemplate" }

$utf8 = New-Object Text.UTF8Encoding $false
$content = [IO.File]::ReadAllText($contentTemplate, $utf8)
$templateVer = '0.4.0.202604042146'
if ($content -notlike "*$templateVer*") {
    throw "Шаблон content.xml не содержит $templateVer - обнови scripts/p2-templates"
}
$content = $content.Replace($templateVer, $version)
$content = $content.Replace("PluginEDT fork v0.4.0-fork.1", $repoName)
$content = $content.Replace('1787723925981', [string]$ts)
New-Utf8NoBomFile -Path (Join-Path $metaWork 'content.xml') -Content $content

# artifacts.xml целиком. Literal here-string: p2-токены ${repoUrl}/${id}/${version} не интерполировать.
$art = @'
<?xml version='1.0' encoding='UTF-8'?>
<?artifactRepository version='1.1.0'?>
<repository name='__REPO_NAME__' type='org.eclipse.equinox.p2.artifact.repository.simpleRepository' version='1'>
  <properties size='2'>
    <property name='p2.timestamp' value='__TS__'/>
    <property name='p2.compressed' value='true'/>
  </properties>
  <mappings size='3'>
    <rule filter='(&amp; (classifier=osgi.bundle))' output='${repoUrl}/plugins/${id}_${version}.jar'/>
    <rule filter='(&amp; (classifier=binary))' output='${repoUrl}/binary/${id}_${version}'/>
    <rule filter='(&amp; (classifier=org.eclipse.update.feature))' output='${repoUrl}/features/${id}_${version}.jar'/>
  </mappings>
  <artifacts size='2'>
    <artifact classifier='osgi.bundle' id='dev.zigr.dt.team.ui.storage' version='__VERSION__'>
      <properties size='1'>
        <property name='download.size' value='__PLUGIN_SIZE__'/>
      </properties>
    </artifact>
    <artifact classifier='org.eclipse.update.feature' id='dev.zigr.dt.team.ui.storage.feature' version='__VERSION__'>
      <properties size='2'>
        <property name='download.contentType' value='application/zip'/>
        <property name='download.size' value='__FEATURE_SIZE__'/>
      </properties>
    </artifact>
  </artifacts>
</repository>
'@
$art = $art.Replace('__REPO_NAME__', $repoName).Replace('__TS__', [string]$ts).Replace('__VERSION__', $version).Replace('__PLUGIN_SIZE__', [string]$pluginSize).Replace('__FEATURE_SIZE__', [string]$featureSize)
New-Utf8NoBomFile -Path (Join-Path $metaWork 'artifacts.xml') -Content $art

Push-Location $metaWork
try {
    & $jarExe cf (Join-Path $repoStage 'content.jar') content.xml
    if ($LASTEXITCODE -ne 0) { throw "jar content.jar failed" }
    & $jarExe cf (Join-Path $repoStage 'artifacts.jar') artifacts.xml
    if ($LASTEXITCODE -ne 0) { throw "jar artifacts.jar failed" }
}
finally { Pop-Location }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$zipPath = Join-Path $OutDir 'build.zip'
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
if (Test-Path (Join-Path $repoStage 'META-INF')) {
    Remove-Item (Join-Path $repoStage 'META-INF') -Recurse -Force
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-ZipForwardSlash -SourceDir $repoStage -ZipPath $zipPath

# Финальная проверка zip (то, что качает пользователь).
$verifyTmp = Join-Path $work 'verify-zip'
New-Item -ItemType Directory $verifyTmp | Out-Null
$outer = [IO.Compression.ZipFile]::OpenRead($zipPath)
try {
    $badSlash = @($outer.Entries | Where-Object { $_.FullName.Contains('\') } | ForEach-Object { $_.FullName })
    if ($badSlash.Count -gt 0) {
        throw "build.zip содержит обратные слэши (p2/Java ждут /): $($badSlash -join ', ')"
    }
    $pe = $outer.Entries | Where-Object { $_.FullName -like "plugins/dev.zigr.dt.team.ui.storage_$version.jar" } | Select-Object -First 1
    if (-not $pe) { throw "В build.zip нет plugins/$pluginJarName" }
    $extracted = Join-Path $verifyTmp 'plugin.jar'
    [IO.Compression.ZipFileExtensions]::ExtractToFile($pe, $extracted, $true)
}
finally { $outer.Dispose() }
Assert-OsgiPluginJar -JarPath $extracted -ExpectedVersion $version

$copyNamed = Join-Path $OutDir "build-$version.zip"
Copy-Item $zipPath $copyNamed -Force

Write-Host ""
Write-Host "BUILD OK"
Write-Host "  $zipPath"
Write-Host "  $copyNamed"
Write-Host "  plugin=$pluginSize feature=$featureSize version=$version"
Write-Host "Проверка MANIFEST: Bundle-SymbolicName + Activator + Require-Bundle - есть."
