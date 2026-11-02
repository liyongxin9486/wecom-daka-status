# Offline parser/widget regression tests (no device needed).
# Compiles the parser sources + test harnesses and writes results to tests\result-*.txt
$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Sdk  = "D:\software\software_data\AndroidSDK"
$JbrBin = "D:\software\anndroid studio\jbr\bin"
$AndroidJar = "$Sdk\platforms\android-35\android.jar"
$Out = "$Root\tests\out"
$SrcDir = "$Root\src\com\daka\status"

$files = @()
foreach ($n in @("StatusParser", "Snapshot", "Prefs", "HolidayHelper", "VdHelper", "WidgetFmt", "RefreshPolicy", "AttendanceCodec", "AttendanceRecord", "ReminderTime")) {
    $files += "$SrcDir\$n.java"
}
$files += "$Root\tests\TestDaka.java", "$Root\tests\TestStale.java", "$Root\tests\TestWidget.java", "$Root\tests\TestRegression.java", "$Root\tests\TestLocalAttendance.java"
$files += "$Root\tests\TestReminderTime.java"

Remove-Item $Out -Recurse -Force -ErrorAction SilentlyContinue
New-Item $Out -ItemType Directory -Force | Out-Null

& "$JbrBin\javac.exe" -encoding UTF-8 -classpath $AndroidJar -d $Out @files
if ($LASTEXITCODE -ne 0) { throw "javac FAILED" }

& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestDaka "$Root\tests\result-daka.txt"
if ($LASTEXITCODE -ne 0) { throw "TestDaka FAILED" }
& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestStale "$Root\tests\result-stale.txt"
if ($LASTEXITCODE -ne 0) { throw "TestStale FAILED" }
& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestWidget "$Root\tests\result-widget.txt"
if ($LASTEXITCODE -ne 0) { throw "TestWidget FAILED" }
& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestRegression | Tee-Object "$Root\tests\result-regression.txt"
if ($LASTEXITCODE -ne 0) { throw "TestRegression FAILED" }
& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestLocalAttendance | Tee-Object "$Root\tests\result-local-record.txt"
if ($LASTEXITCODE -ne 0) { throw "TestLocalAttendance FAILED" }

Write-Host ""
& "$JbrBin\java.exe" -classpath "$Out;$AndroidJar" com.daka.status.TestReminderTime | Tee-Object "$Root\tests\result-reminder-time.txt"
if ($LASTEXITCODE -ne 0) { throw "TestReminderTime FAILED" }

Write-Host "TEST RUN OK -> tests\result-daka.txt / tests\result-stale.txt / tests\result-widget.txt" -ForegroundColor Green
