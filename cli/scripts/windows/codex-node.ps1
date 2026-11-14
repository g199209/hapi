param([Parameter(Mandatory=$true)][string]$ConfigPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -Path (Join-Path $PSScriptRoot 'process-environment.cs')
$config = Get-Content -Raw -Encoding UTF8 -LiteralPath $ConfigPath | ConvertFrom-Json
$nodeRoot = Split-Path -Parent ([IO.Path]::GetFullPath($ConfigPath))
$statePath = Join-Path $nodeRoot 'node-state.json'
$stopPath = Join-Path $nodeRoot 'stop'
$utf8 = New-Object Text.UTF8Encoding($false)
$identity = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($nodeRoot)).Replace('/', '_')
$mutex = New-Object Threading.Mutex($false, ('Local\HAPI-Codex-' + $identity))
try { $acquired = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
if (!$acquired) { $mutex.Dispose(); exit 0 }

function Write-Json($path, $value) {
    [IO.File]::WriteAllText(($path + '.tmp'), (ConvertTo-Json -InputObject $value -Depth 12), $utf8)
    Move-Item -Force -LiteralPath ($path + '.tmp') -Destination $path
}
function Quote-Argument([string]$value) {
    return '"' + (($value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Get-Child($child) {
    if ($null -eq $child) { return $null }
    $startedPath = Join-Path $child.runDirectory 'started.json'
    if (!(Test-Path -LiteralPath $startedPath)) { return $null }
    $started = Get-Content -Raw -Encoding UTF8 -LiteralPath $startedPath | ConvertFrom-Json
    $process = Get-Process -Id $started.processId -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    # Do not adopt a reused PID, another executable, or a completed launcher run.
    if ((Test-Path (Join-Path $child.runDirectory 'result.json')) -or
        $process.Path -ine $child.executable -or
        [Math]::Abs(($process.StartTime.ToUniversalTime() - [DateTime]::Parse($started.startedAt).ToUniversalTime()).TotalSeconds) -gt 10) { return $null }
    return $process
}
function Start-Child($name, $executable, $arguments, $environment) {
    $run = Join-Path $nodeRoot ('runs\' + $name + '-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $request = @{fileName=$executable; arguments=(($arguments | ForEach-Object { Quote-Argument $_ }) -join ' '); workingDirectory=$config.codexHome; environment=$environment}
    Write-Json (Join-Path $run 'request.json') $request
    $state.$name = @{runDirectory=$run; executable=$executable}
    Write-Json $statePath $state
    $null = [HapiProcessEnvironment]::StartDetached($config.hiddenLauncher, (Quote-Argument (Join-Path $run 'request.json')), $nodeRoot)
    for ($i=0; $i -lt 100; $i++) {
        $process = Get-Child $state.$name
        if ($null -ne $process) { return $process }
        if (Test-Path (Join-Path $run 'result.json')) { throw "$name exited during startup; inspect $run" }
        Start-Sleep -Milliseconds 100
    }
    throw "$name startup acknowledgement timed out; inspect $run"
}
function Engine-Ready {
    try {
        $health = Invoke-WebRequest -UseBasicParsing -Uri ($config.endpoint.Replace('ws:', 'http:') + '/readyz') -TimeoutSec 2
        return $health.StatusCode -eq 200
    } catch { return $false }
}
function Refresh-Desktop {
    $app = Get-CimInstance Win32_Process -Filter "Name = 'ChatGPT.exe'" | Where-Object {
        $_.ExecutablePath -match 'OpenAI.Codex_' -and $_.CommandLine -notmatch '--type='
    } | Select-Object -First 1
    $desktop = $null
    if ($null -ne $app) {
        $environment = [HapiProcessEnvironment]::Read($app.ProcessId)
        if ($environment.ContainsKey('CODEX_APP_TOOLS_PIPE_PATH') -and $environment.ContainsKey('CODEX_TASK_WORKSPACE_VERIFYING_IDENTITY')) {
            $desktop = @{pid=$app.ProcessId; pipePath=$environment['CODEX_APP_TOOLS_PIPE_PATH']; verifyingIdentity=$environment['CODEX_TASK_WORKSPACE_VERIFYING_IDENTITY']}
        }
    }
    $path = Join-Path $nodeRoot 'desktop.json'
    $next = ConvertTo-Json -InputObject $desktop -Compress
    if (!(Test-Path $path) -or [IO.File]::ReadAllText($path).Trim() -ne $next) { Write-Json $path $desktop }
}

try {
    if (Test-Path $statePath) { $state = Get-Content -Raw -Encoding UTF8 -LiteralPath $statePath | ConvertFrom-Json }
    else { $state = [pscustomobject]@{engine=$null; runner=$null; bridge=$null; lastHealthyAt=$null; lastError=$null} }
    $withBridge = $null -ne $config.PSObject.Properties['appToolsBridge']
    if ($withBridge) {
        if ($null -eq $state.PSObject.Properties['bridge']) { $state | Add-Member -NotePropertyName bridge -NotePropertyValue $null }
    }
    $engineEnv = @{}
    foreach ($property in $config.engineEnvironment.PSObject.Properties) { $engineEnv[$property.Name] = [string]$property.Value }
    $engineEnv['CODEX_HOME'] = $config.codexHome
    $engineEnv['PATH'] = (Split-Path -Parent $config.codexPath) + ';' + $engineEnv['PATH']
    $runnerEnv = @{
        CODEX_HOME=$config.codexHome; HAPI_HOME=$config.hapiHome; HAPI_API_URL=$config.apiUrl;
        HAPI_CODEX_APP_SERVER_ENDPOINT=$config.endpoint; HAPI_HOSTNAME=$config.hostName;
        HAPI_RUNNER_SUPERVISED='1'; HAPI_DISABLE_VERSION_HANDOFF='1'; PATH=$engineEnv['PATH']
    }
    if ($null -ne $config.PSObject.Properties['runnerEnvironment']) {
        foreach ($property in $config.runnerEnvironment.PSObject.Properties) { $runnerEnv[$property.Name] = [string]$property.Value }
    }
    $runnerArgs = @('runner', 'start-sync')
    foreach ($workspace in $config.workspaceRoots) { $runnerArgs += @('--workspace-root', $workspace) }
    while (!(Test-Path $stopPath)) {
        try {
            if ($withBridge) {
                Refresh-Desktop
                if ($null -eq (Get-Child $state.bridge)) {
                    $null = Start-Child 'bridge' $config.appToolsBridge.nodePath @((Join-Path $PSScriptRoot 'codex-app-tools-bridge.cjs'), $nodeRoot, $config.appToolsBridge.pipePath) @{}
                }
                $publicPath = Join-Path $nodeRoot 'app-tools-public.txt'
                if (!(Test-Path $publicPath)) { throw 'Waiting for App tools bridge identity' }
                $engineEnv['CODEX_APP_TOOLS_PIPE_PATH'] = $config.appToolsBridge.pipePath
                $engineEnv['CODEX_TASK_WORKSPACE_VERIFYING_IDENTITY'] = (Get-Content -Raw $publicPath).Trim()
            }
            $engine = Get-Child $state.engine
            if ($null -eq $engine) {
                if (Engine-Ready) { throw 'Endpoint is already in use by an untracked engine; inspect it before recovery' }
                $engine = Start-Child 'engine' $config.codexPath (@($config.engineArguments) + @('--listen', $config.endpoint)) $engineEnv
            }
            if (Engine-Ready) {
                if ($null -eq (Get-Child $state.runner)) { $null = Start-Child 'runner' $config.hapiPath $runnerArgs $runnerEnv }
                $state.lastHealthyAt = [DateTime]::UtcNow.ToString('o')
                $state.lastError = $null
            }
            Write-Json $statePath $state
        } catch {
            $state.lastError = $_.Exception.Message
            Write-Json $statePath $state
            [Console]::Error.WriteLine($state.lastError)
        }
        Start-Sleep -Seconds 3
    }
    # Maintenance stop detaches supervision; native work and HAPI wrappers remain alive.
} finally {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
