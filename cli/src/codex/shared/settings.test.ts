import { describe, expect, it } from 'vitest';
import { inheritedSandbox, settingsMatch } from './settings';

describe('native shared settings', () => {
    it('does not acknowledge another client changing an unrelated field', () => {
        expect(settingsMatch({ model: 'a', effort: 'high' }, { threadId: 't', model: 'b' })).toBe(false);
        expect(settingsMatch({ model: 'b', effort: 'high' }, { threadId: 't', model: 'b' })).toBe(true);
        expect(settingsMatch({ serviceTier: 'priority' }, { serviceTier: null })).toBe(false);
    });
    it('matches collaboration settings including model and effort, but accepts expanded built-in instructions', () => {
        const expected = { collaborationMode: { mode: 'plan', settings: { model: 'b', reasoning_effort: 'high', developer_instructions: null } } };
        expect(settingsMatch({ collaborationMode: { mode: 'plan', settings: { model: 'a', reasoning_effort: 'high' } } }, expected)).toBe(false);
        expect(settingsMatch({ collaborationMode: { mode: 'plan', settings: { model: 'b', reasoning_effort: 'high', developer_instructions: 'native instructions' } } }, expected)).toBe(true);
    });
    it('preserves workspace sandbox restrictions and never widens an external sandbox', () => {
        expect(inheritedSandbox({ sandboxPolicy: { type: 'workspaceWrite', writableRoots: ['/project'], networkAccess: false,
            excludeTmpdirEnvVar: true, excludeSlashTmp: true } })).toEqual({ sandbox: 'workspace-write', config: {
            'sandbox_workspace_write.writable_roots': ['/project'], 'sandbox_workspace_write.network_access': false,
            'sandbox_workspace_write.exclude_tmpdir_env_var': true, 'sandbox_workspace_write.exclude_slash_tmp': true
        } });
        expect(() => inheritedSandbox({ sandboxPolicy: { type: 'externalSandbox' } })).toThrow('Cannot inherit');
    });
    it('inherits named and built-in profile ids without combining them with a legacy sandbox', () => {
        for (const id of ['restricted', ':workspace', ':danger-full-access']) {
            expect(inheritedSandbox({ activePermissionProfile: { id, extends: ':workspace' },
                sandboxPolicy: { type: 'workspaceWrite', writableRoots: ['/project'] } })).toEqual({ permissions: id });
        }
    });
    it('rejects an invalid named profile instead of falling back to another permission mode', () => {
        expect(() => inheritedSandbox({ activePermissionProfile: {} })).toThrow('without its id');
        expect(() => inheritedSandbox({ activePermissionProfile: { id: ' ' } })).toThrow('without its id');
    });
});
