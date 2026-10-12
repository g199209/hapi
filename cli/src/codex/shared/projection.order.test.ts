import { describe, expect, it, vi } from 'vitest';
import type { ApiSessionClient } from '@/api/apiSession';
import { SharedCodexProjection } from './projection';
function fixture() {
    const user = vi.fn(), agent = vi.fn();
    const projection = new SharedCodexProjection({ getMetadata: () => ({ codexReasoningReplayExcluded: [] }),
        updateMetadata: vi.fn(), sendUserMessage: user, sendAgentMessage: agent } as unknown as ApiSessionClient, 'thread', async () => {});
    return { projection, user, agent };
}
const input = (id: string, text = 'repeat', clientId?: string) => ({ id, type: 'userMessage', content: [{ type: 'text', text }], clientId });
describe('native history identity and ordering', () => {
    it('keeps one identity when a snapshot placeholder becomes a native UUID', async () => {
        const { projection, user } = fixture();
        const history = (id: string, status: string) => ({ turns: [{ id: 'turn', startedAt: 1000, status, items: [input(id)] }] });
        await projection.history(history('item-1', 'inProgress'));
        await projection.history(history('native-uuid', 'completed'));
        projection.reset(); await projection.history(history('native-uuid', 'completed'));
        expect(new Set(user.mock.calls.map(call => call[2])).size).toBe(1);
        expect(user.mock.calls.every(call => call[3] === 1000000)).toBe(true);
    });
    it('retains repeated prompts in the same and different turns and preserves client IDs', async () => {
        const { projection, user } = fixture();
        await projection.history({ turns: [
            { id: 'a', startedAt: 1000, status: 'completed', items: [input('1'), input('2')] },
            { id: 'b', startedAt: 2000, status: 'completed', items: [input('1')] },
            { id: 'c', startedAt: 3000, status: 'completed', items: [input('1', 'repeat', 'local-composer')] }
        ] });
        expect(new Set(user.mock.calls.map(call => call[2])).size).toBe(4);
        expect(user.mock.calls.at(-1)?.[2]).toBe('local-composer');
    });
    it('appends new live items after reconnect and locates delayed completion at its start', async () => {
        const { projection, agent } = fixture();
        await projection.history({ turns: [{ id: 'turn', startedAt: 1000, status: 'inProgress', items: [] }] });
        const before = Date.now();
        await projection.notification('item/started', { threadId: 'thread', turnId: 'turn', item: { id: 'preface', type: 'agentMessage', text: '' } });
        await projection.notification('item/started', { threadId: 'thread', turnId: 'turn', item: { id: 'command', type: 'commandExecution', command: 'pwd' } });
        await projection.notification('item/completed', { threadId: 'thread', turnId: 'turn', item: { id: 'preface', type: 'agentMessage', text: 'preface' } });
        const preface = agent.mock.calls.find(call => call[0].type === 'message');
        const command = agent.mock.calls.find(call => call[0].type === 'tool-call');
        expect(preface?.[2]).toBeGreaterThanOrEqual(before);
        expect(preface?.[2]).toBeLessThan(command?.[2]);
    });
    it('emits a settled preface from an active snapshot and holds the trailing partial answer', async () => {
        const { projection, agent } = fixture();
        const preface = { id: 'preface', type: 'agentMessage', text: 'before tools' };
        const final = { id: 'answer', type: 'agentMessage', text: 'partial' };
        await projection.history({ turns: [{ id: 'turn', startedAt: 1000, status: 'inProgress', items: [
            preface, { id: 'command', type: 'commandExecution', command: 'pwd', status: 'completed', aggregatedOutput: '/tmp' }, final
        ] }] });
        expect(agent.mock.calls.filter(call => call[0].type === 'message').map(call => call[0].message)).toEqual(['before tools']);
        await projection.notification('item/completed', { threadId: 'thread', turnId: 'turn', item: { ...final, text: 'final' } });
        const messages = agent.mock.calls.filter(call => call[0].type === 'message');
        expect(messages.map(call => call[0].message)).toEqual(['before tools', 'final']);
        expect(messages[0][2]).toBeLessThan(messages[1][2]);
    });
});
