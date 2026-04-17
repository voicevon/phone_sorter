# Android Environment Health Check
$status = "OK"

if (-not $env:JAVA_HOME) {
    Write-Warning "JAVA_HOME is not set."
    $status = "MISSING"
}
else {
    Write-Host "JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green
}

if (-not $env:ANDROID_HOME) {
    Write-Warning "ANDROID_HOME is not set."
    $status = "MISSING"
}
else {
    Write-Host "ANDROID_HOME: $env:ANDROID_HOME" -ForegroundColor Green
}

if ($env:Path -notmatch "platform-tools") {
    Write-Warning "adb (platform-tools) not found in Path."
    $status = "MISSING"
}
else {
    Write-Host "ADB in Path: OK" -ForegroundColor Green
}

Write-Host "Environment Status: $status"
if ($status -eq "MISSING") {
    Write-Host "Please check docs/ENVIRONMENT_SETUP.md for configuration."
}
