# Native Windows shared Codex node

`codex-node.ps1` supervises one external app-server and one native HAPI Runner.
It uses the windowless `HapiHiddenProcess.exe` launcher from
`android/scripts/windows`, with separate binary stdout/stderr logs per child.
Engine and Runner failures restart independently. Supervisor restarts adopt
their recorded live child generations; they never start a replacement engine
over an unknown listener. A `stop` file beside the configuration pauses
supervision while leaving native work alive.

Install the scripts in one Windows node directory. Create `node.json` with:

```json
{
  "codexPath": "C:\\path\\to\\codex.exe",
  "hapiPath": "C:\\path\\to\\hapi.exe",
  "hiddenLauncher": "C:\\Users\\you\\AppData\\Local\\HAPI\\android-tools\\HapiHiddenProcess.exe",
  "codexHome": "C:\\Users\\you\\.codex",
  "hapiHome": "C:\\Users\\you\\.hapi",
  "apiUrl": "https://your-hapi-hub",
  "hostName": "Windows",
  "endpoint": "ws://127.0.0.1:4513",
  "workspaceRoots": ["C:\\Users\\you"],
  "engineArguments": ["-c", "features.code_mode_host=true", "app-server"],
  "engineEnvironment": {"PATH": "your Windows PATH"}
}
```

Preserve the desktop engine's feature flags and relevant runtime environment,
including `CODEX_MCP_NODE_PATH` / Windows sandbox package identity when present.
Keep a versioned copy of the Codex runtime binaries and bundled Node runtime in
the node directory, and point the configuration at those copies. Desktop App
cache directories can be cleaned during App updates. Record source paths and
hashes; upgrade this runtime deliberately while its work is idle.
Put the Hub CLI token and an independent machine ID in the Windows HAPI home's
`settings.json`. Never copy a WSL machine ID or point Windows at its native store.

Create a `supervisor-request.json` for the GUI launcher with `fileName` set to
Windows PowerShell, `arguments` to
`-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "...\codex-node.ps1" -ConfigPath "...\node.json"`,
`workingDirectory` to the node directory, and `environment` to an empty object.
Run `start-codex-node.vbs` through `wscript.exe //B //Nologo`. A Task Scheduler
logon task can run the same VBS with restart-on-failure enabled and no execution
time limit. The VBS waits for supervision and propagates its exit code.
The supervisor starts GUI helpers with `CreateProcessW`, `CREATE_NO_WINDOW` and
handle inheritance disabled. This lets supervision finish while its children
keep running; each helper still redirects and drains its child's output.

For desktop App tools, also install `codex-app-tools-bridge.cjs` and
`process-environment.cs` beside the supervisor and configure:

```json
"appToolsBridge": {
  "nodePath": "C:\\path\\to\\desktop-bundled\\node.exe",
  "pipePath": "\\\\.\\pipe\\hapi-codex-app-tools-your-user"
}
```

The supervisor discovers the current desktop's App tools pipe and verification
key from its process environment. The bridge provides a persistent local pipe
and identity for the engine, follows desktop restarts, verifies signed desktop
task responses and re-signs them for that identity. The private key stays in the
node directory. Closing the desktop makes its App tools unavailable while the
engine continues ordinary work; reopening it restores the desktop endpoint.
Discovery uses the installed x64 `OpenAI.Codex` desktop process.

After the App is idle, close its stdio engine, start this node, and launch the
desktop App with `CODEX_APP_SERVER_WS_URL` set to the node endpoint. Preserve
that environment for later App launches. Verify an existing thread in both
clients, inherited permissions, native queue / questions, and reconnection
before treating the switch as complete. Keep the previous App launch environment
and node configuration for rollback.
