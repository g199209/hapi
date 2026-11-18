# Native OpenCode / DSH sessions

These machine-scoped APIs use the usual client Bearer JWT and namespace.
The Runner advertises optional `listNativeSessions` capability; an older Runner
returns an upgrade-required response without affecting existing agent support.

- `GET /api/native/sessions?machineId=<id>&flavor=opencode|dsh` returns
  `{success, machineId, sessions, error?}`. Each row has `id`, `title`, `cwd`,
  `modifiedAt`, optional `basePath`, `runtime`, `profile`, and `active`.
  `basePath` groups linked Git worktrees. Results are limited to that Runner's
  workspace roots.
- `POST /api/native/import` accepts `{machineId, flavor, sessionIds: string[]}`
  (1–20 IDs). All selections are validated before creating bindings. It returns
  `{success, hapiSessionIds}`; repeated imports reuse the same binding. Call the
  usual `/api/sessions/:id/resume` and follow its returned ID before opening chat.

Imported metadata sets `nativeShared`, `nativeRuntime`, optional `nativeProfile`,
and the flavor's `opencodeSessionId` / `dshSessionId`. Native IDs are opaque.
`agentState.nativeRuntimeStatus` is `connected` or `disconnected`;
`nativeCompacting` marks active native compaction. Connection loss is displayed
in the existing session status; queued inputs remain pending until native history
confirms them. Normal question/approval APIs reply directly to the native runtime.

The internal CLI `native-message-batch` socket event carries stable native
message IDs and integral canonical ordering positions. Mutable content updates
retain the HAPI row ID; full baselines reconcile only their own source. Client
message paging, SSE updates, epochs and optimistic input IDs keep the normal
chat contract. Historical replay does not create new unread activity.
