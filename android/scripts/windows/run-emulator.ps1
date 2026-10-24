$ErrorActionPreference = 'Stop'
$tools = Join-Path $env:LOCALAPPDATA 'HAPI\android-tools'
$sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'

function Invoke-HapiHiddenCommand([string]$FileName, [string]$Arguments) {
    $run = Join-Path $tools ('runs\' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $request = Join-Path $run 'request.json'
    $spec = @{ fileName = $FileName; arguments = $Arguments; workingDirectory = $sdk
        environment = @{ ANDROID_ADB_SERVER_PORT = '5038'; ADB_SERVER_SOCKET = 'tcp:localhost:5038' } }
    [IO.File]::WriteAllText($request, ($spec | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $tools 'HapiHiddenProcess.exe'
    $start.Arguments = '"' + $request + '"'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    $process.WaitForExit()
    $result = Get-Content (Join-Path $run 'result.json') -Raw | ConvertFrom-Json
    if ($result.error) { throw $result.error }
    if ($result.exitCode -ne 0) { throw "Android command exited $($result.exitCode); logs: $run" }
}

Invoke-HapiHiddenCommand "$sdk\platform-tools\adb.exe" '-P 5038 start-server'
Invoke-HapiHiddenCommand "$sdk\emulator\emulator.exe" '-avd HapiValidation_API_36 -port 5580 -no-snapshot -no-boot-anim -gpu swiftshader_indirect -no-audio -no-window'
