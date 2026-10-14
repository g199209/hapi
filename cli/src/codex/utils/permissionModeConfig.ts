import type { CodexPermissionMode } from '@hapi/protocol/types';
import type { ApprovalPolicyPreset, SandboxMode, SandboxPolicy } from '../appServerTypes';

export type CodexPermissionModeConfig = {
    approvalPolicy: ApprovalPolicyPreset;
    sandbox: SandboxMode;
    sandboxPolicy: SandboxPolicy;
};

export function resolveCodexPermissionModeConfig(mode: 'default'): undefined;
export function resolveCodexPermissionModeConfig(mode: Exclude<CodexPermissionMode, 'default'>): CodexPermissionModeConfig;
export function resolveCodexPermissionModeConfig(mode: CodexPermissionMode): CodexPermissionModeConfig | undefined;
export function resolveCodexPermissionModeConfig(mode: CodexPermissionMode): CodexPermissionModeConfig | undefined {
    switch (mode) {
        case 'default':
            // Inherit the native config/current thread; Default is not a preset.
            return undefined;
        case 'read-only':
            return {
                approvalPolicy: 'never',
                sandbox: 'read-only',
                sandboxPolicy: { type: 'readOnly' }
            };
        case 'safe-yolo':
            return {
                // Current Codex versions reject the removed `on-failure` policy. Keep
                // escalation available through `on-request`; HAPI auto-approves these
                // requests in safe-yolo mode.
                approvalPolicy: 'on-request',
                sandbox: 'workspace-write',
                sandboxPolicy: { type: 'workspaceWrite' }
            };
        case 'yolo':
            return {
                approvalPolicy: 'never',
                sandbox: 'danger-full-access',
                sandboxPolicy: { type: 'dangerFullAccess' }
            };
    }

    const unexpectedMode: never = mode;
    throw new Error(`Unknown permission mode: ${unexpectedMode}`);
}

export function buildCodexPermissionModeCliArgs(mode: Exclude<CodexPermissionMode, 'default'>): string[] {
    const config = resolveCodexPermissionModeConfig(mode);
    return ['--ask-for-approval', config.approvalPolicy, '--sandbox', config.sandbox];
}
