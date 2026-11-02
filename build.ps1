# build.ps1 - daka-status APK build (no Gradle: aapt2 + javac + d8 + zipalign + apksigner)
# NOTE: aapt2 on Windows cannot handle non-ASCII project paths, so everything is
# staged under an ASCII temp dir and only the final APK is copied back.
$ErrorActionPreference = "Stop"

$Root    = Split-Path -Parent $MyInvocation.MyCommand.Path
$Stage   = "$env:TEMP\opencode\daka-build"
$App     = "$Stage\app"
$Build   = "$Stage\out"

$Sdk     = "D:\software\software_data\AndroidSDK"
$BT      = "$Sdk\build-tools\35.0.0"
$JbrHome = "D:\software\anndroid studio\jbr"
$JbrBin  = "$JbrHome\bin"
$AndroidJar = "$Sdk\platforms\android-35\android.jar"

$Aapt2      = "$BT\aapt2.exe"
$D8         = "$BT\d8.bat"
$Zipalign   = "$BT\zipalign.exe"
$Apksigner  = "$BT\apksigner.bat"
$Javac      = "$JbrBin\javac.exe"
$Keytool    = "$JbrBin\keytool.exe"

$KsPass        = "dakapass"
$KeystoreStage = "$Stage\daka.keystore"
$KeystoreHome  = "$Root\daka.keystore"
$OutApk        = "$Root\daka-status.apk"

$env:JAVA_HOME = $JbrHome

# Build version: epoch seconds + readable timestamp so builds are distinguishable
$verCode = [int][DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$verName = "1.0." + (Get-Date -Format "yyMMdd.HHmm")

function Step([string]$name) { Write-Host ""; Write-Host "==== $name ====" -ForegroundColor Cyan }
function Check([string]$what) {
    if ($LASTEXITCODE -ne 0) { throw "$what FAILED (exit $LASTEXITCODE)" }
}
# Runs a native tool that may write to stderr (Java tools do), capturing all
# output to a log file. PowerShell 5.1 turns native stderr into a terminating
# error under ErrorActionPreference=Stop, so it is relaxed around the call.
function RunTool([string]$exe, [string[]]$toolArgs, [string]$log) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $exe @toolArgs *> $log
    $code = $LASTEXITCODE
    $ErrorActionPreference = $old
    if ($code -ne 0) { Get-Content $log -ErrorAction SilentlyContinue | Write-Host }
    return $code
}

Step "clean + stage sources"
Remove-Item $Stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item "$App" -ItemType Directory -Force | Out-Null
New-Item "$Build\gen" -ItemType Directory -Force | Out-Null
New-Item "$Build\classes" -ItemType Directory -Force | Out-Null
New-Item "$Build\dex" -ItemType Directory -Force | Out-Null
Copy-Item "$Root\res" -Destination "$App\res" -Recurse
Copy-Item "$Root\src" -Destination "$App\src" -Recurse
Copy-Item "$Root\AndroidManifest.xml" "$App\AndroidManifest.xml"

Step "aapt2 compile"
& $Aapt2 compile --dir "$App\res" -o "$Build\res.zip"
Check "aapt2 compile"

Step "aapt2 link"
& $Aapt2 link -o "$Build\base.apk" `
    -I $AndroidJar `
    --manifest "$App\AndroidManifest.xml" `
    -R "$Build\res.zip" `
    --java "$Build\gen" `
    --min-sdk-version 26 `
    --target-sdk-version 33 `
    --version-code $verCode `
    --version-name "$verName" `
    --auto-add-overlay
Check "aapt2 link"

Step "javac"
$javaFiles = @()
$javaFiles += (Get-ChildItem "$App\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += (Get-ChildItem "$Build\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("java files: " + $javaFiles.Count)
& $Javac -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath $AndroidJar `
    -classpath $AndroidJar `
    -d "$Build\classes" `
    @javaFiles
Check "javac"

Step "d8"
$classFiles = Get-ChildItem "$Build\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$dexInputs = "$Build\d8-inputs.txt"
[System.IO.File]::WriteAllLines($dexInputs, [string[]]$classFiles, (New-Object System.Text.UTF8Encoding($false)))
& $D8 --release --min-api 26 --lib $AndroidJar --output "$Build\dex" "@$dexInputs"
Check "d8"
if (-not (Test-Path "$Build\dex\classes.dex")) { throw "d8 did not produce classes.dex" }

Step "pack classes.dex into APK"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$Build\base.apk" "$Build\unsigned.apk" -Force
$zip = [System.IO.Compression.ZipFile]::Open("$Build\unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = $zip.CreateEntry("classes.dex", [System.IO.Compression.CompressionLevel]::Optimal)
    $es = $entry.Open()
    try {
        $fs = [System.IO.File]::OpenRead("$Build\dex\classes.dex")
        try { $fs.CopyTo($es) } finally { $fs.Dispose() }
    } finally { $es.Dispose() }
} finally { $zip.Dispose() }

Step "zipalign"
& $Zipalign -f 4 "$Build\unsigned.apk" "$Build\aligned.apk"
Check "zipalign"

if (-not (Test-Path $KeystoreStage)) {
    if (Test-Path $KeystoreHome) {
        Copy-Item $KeystoreHome $KeystoreStage
    } else {
        Step "generate keystore"
        $code = RunTool $Keytool @(
            "-genkeypair", "-keystore", $KeystoreStage, "-alias", "daka",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "12000",
            "-storepass", $KsPass, "-keypass", $KsPass,
            "-dname", "CN=daka-status, OU=daka, O=daka, L=Taiyuan, ST=Shanxi, C=CN"
        ) "$Build\keytool.log"
        if ($code -ne 0) { throw "keytool FAILED ($code)" }
        Copy-Item $KeystoreStage $KeystoreHome
    }
}

Step "apksigner sign"
$code = RunTool $Apksigner @(
    "sign", "--ks", $KeystoreStage, "--ks-pass", "pass:$KsPass", "--key-pass", "pass:$KsPass",
    "--out", "$Build\daka-status.apk", "$Build\aligned.apk"
) "$Build\sign.log"
if ($code -ne 0) { throw "apksigner sign FAILED ($code)" }

Step "verify + copy out"
$code = RunTool $Apksigner @("verify", "--print-certs", "$Build\daka-status.apk") "$Build\verify.log"
if ($code -ne 0) { throw "apksigner verify FAILED ($code)" }
Get-Content "$Build\verify.log" | Select-Object -First 4 | Write-Host
Copy-Item "$Build\daka-status.apk" $OutApk -Force

$size = [math]::Round((Get-Item $OutApk).Length / 1KB, 1)
Write-Host ""
Write-Host "BUILD OK -> $OutApk ($size KB) version=$verName ($verCode)" -ForegroundColor Green
