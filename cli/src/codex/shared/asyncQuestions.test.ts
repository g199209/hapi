import { afterEach, describe, expect, it, vi } from 'vitest';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import type { ApiSessionClient } from '@/api/apiSession';
import type { AgentState } from '@/api/types';
import { SharedCodexAsyncQuestions, asyncQuestionsInput } from './asyncQuestions';

const dirs: string[] = [];
afterEach(async () => { for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }); });
const item = { type: 'agentMessage', id: 'ask', delivery: 'async', text: 'plain fallback',
    questions: [{ title: 'Choose a layout', options: ['Compact', 'Expanded'] }, { title: 'Any notes?' }] };
const key = 'codex-async:thread:ask';
const answers = { '0': { answers: ['Compact'] }, '1': { answers: ['Use small text'] } };

async function fixture() {
    const dir = await mkdtemp('/tmp/hapi-async-questions-'); dirs.push(dir);
    let state: AgentState = {};
    const send = vi.fn(); const submit = vi.fn(async (_id: string, _text: string) => {});
    const session = { sendAgentMessage: send,
        updateAgentState: (fn: (s: AgentState) => AgentState) => { state = fn(state); }
    } as unknown as ApiSessionClient;
    const reopen = async () => {
        const bridge = new SharedCodexAsyncQuestions(session, 'thread', join(dir, 'questions.json'), submit);
        await bridge.load(); return bridge;
    };
    return { bridge: await reopen(), reopen, send, submit, state: () => state };
}

describe('Codex asynchronous question bridge', () => {
    it('converts option and free-form questions to existing interactive controls', async () => {
        expect(asyncQuestionsInput(item)?.questions).toEqual([
            { id: '0', question: 'Choose a layout', isOther: true, options: [{ label: 'Compact', description: '' }, { label: 'Expanded', description: '' }] },
            { id: '1', question: 'Any notes?', isOther: true, options: [] },
        ]);
        expect(asyncQuestionsInput({ ...item, delivery: null })).toBeUndefined();
        const f = await fixture(); await f.bridge.receive(item); await f.bridge.receive(item);
        expect(f.send).toHaveBeenCalledTimes(1);
        expect(f.state().requests?.[key]).toMatchObject({ tool: 'request_user_input', toolCallId: key });
    });

    it('submits exactly one correlated user reply and preserves the answer after restart', async () => {
        const f = await fixture(); await f.bridge.receive(item);
        expect(await f.bridge.answer({ id: key, approved: true, answers })).toBe(true);
        expect(f.submit).toHaveBeenCalledWith(`${key}:answer`, 'Choose a layout\nCompact\n\nAny notes?\nUse small text');
        expect(f.state().requests?.[key]).toBeUndefined();
        expect(f.state().completedRequests?.[key]).toMatchObject({ status: 'approved', answers });
        const again = await f.reopen(); await again.receive(item);
        expect(await again.answer({ id: key, approved: true, answers })).toBe(false);
        expect(f.submit).toHaveBeenCalledTimes(1);
    });

    it('requires all answers and skips without sending a reply or interrupting execution', async () => {
        const f = await fixture(); await f.bridge.receive(item);
        await expect(f.bridge.answer({ id: key, approved: true, answers: { '0': ['Compact'] } })).rejects.toThrow('Missing answer');
        expect(await f.bridge.answer({ id: key, approved: false })).toBe(true);
        expect(f.submit).not.toHaveBeenCalled();
        expect(f.state().completedRequests?.[key]?.status).toBe('canceled');
    });

    it('keeps failed delivery actionable and prevents changing an uncertain answer across restart', async () => {
        const f = await fixture(); await f.bridge.receive(item);
        f.submit.mockRejectedValueOnce(new Error('ACK lost'));
        await expect(f.bridge.answer({ id: key, approved: true, answers })).rejects.toThrow('ACK lost');
        expect(f.state().requests?.[key]).toBeDefined();
        const again = await f.reopen(); await again.receive(item);
        await expect(again.answer({ id: key, approved: true, answers: { ...answers, '0': ['Expanded'] } })).rejects.toThrow('previous answer');
        await again.answer({ id: key, approved: true, answers });
        expect(f.submit.mock.calls.map(([id]) => id)).toEqual([`${key}:answer`, `${key}:answer`]);
    });

    it('prevents simultaneous double submissions while native acceptance is pending', async () => {
        const f = await fixture(); await f.bridge.receive(item);
        let release!: () => void;
        f.submit.mockImplementation(() => new Promise<void>(resolve => { release = resolve; }));
        const first = f.bridge.answer({ id: key, approved: true, answers });
        await vi.waitFor(() => expect(release).toBeTypeOf('function'));
        await expect(f.bridge.answer({ id: key, approved: true, answers })).rejects.toThrow('already being submitted');
        release(); await first;
        expect(f.submit).toHaveBeenCalledTimes(1);
    });

    it('does not resurrect a historical question after a later free-text response', async () => {
        const f = await fixture();
        const thread = { turns: [{ items: [item, { type: 'userMessage', clientId: 'desktop-response' }] }] };
        await f.bridge.history(thread); await f.bridge.history(thread);
        expect(f.state().requests?.[key]).toBeUndefined();
        expect(f.state().completedRequests?.[key]).toBeUndefined();
        expect(f.send).not.toHaveBeenCalled();
    });

    it('recovers confirmed submission from native history without repeating delivery', async () => {
        const f = await fixture(); await f.bridge.receive(item);
        await f.bridge.userMessage({ type: 'userMessage', clientId: `${key}:answer` });
        expect(f.state().completedRequests?.[key]?.status).toBe('resolved');
        expect(f.submit).not.toHaveBeenCalled();
    });
});
