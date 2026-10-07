import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { z } from 'zod';
import type { ApiSessionClient } from '@/api/apiSession';
import { record, string } from './gateway';

const Questions = z.array(z.object({ title: z.string().min(1), options: z.array(z.string()).optional() })).min(1);
const Answers = z.record(z.string(), z.object({ answers: z.array(z.string()) }));
const Settled = z.record(z.string(), z.object({ status: z.enum(['approved', 'canceled', 'resolved']), answers: Answers.optional() }));
const Journal = z.object({ settled: Settled, attempts: z.record(z.string(), Answers) });
type AnswerMap = z.infer<typeof Answers>;
type Reply = { id: string; approved: boolean; answers?: Record<string, string[] | { answers: string[] }> };
type Question = { itemId: string; input: ReturnType<typeof asyncQuestionsInput>; sending: boolean };

export function asyncQuestionsInput(item: Record<string, unknown>) {
    if (item.type !== 'agentMessage' || item.delivery !== 'async') return;
    const parsed = Questions.safeParse(item.questions);
    if (!parsed.success) return;
    return { delivery: 'async', isBlocking: false, questions: parsed.data.map((q, index) => ({
        id: String(index), question: q.title, isOther: true,
        options: (q.options ?? []).map(label => ({ label, description: '' })),
    })) };
}

/** Async questions are ordinary agent messages, NOT suspended server RPCs.
 * Reuse HAPI's question controls, but submit through the native user queue. */
export class SharedCodexAsyncQuestions {
    private settled: z.infer<typeof Settled> = {};
    private attempts: Record<string, AnswerMap> = {};
    private pending = new Map<string, Question>();
    private published = new Set<string>();
    private writes = Promise.resolve();
    constructor(private readonly session: ApiSessionClient, private readonly threadId: string,
        private readonly file: string, private readonly submit: (id: string, text: string) => Promise<void>) {}

    async load(): Promise<void> {
        try {
            const journal = Journal.parse(JSON.parse(await readFile(this.file, 'utf8')));
            this.settled = journal.settled; this.attempts = journal.attempts;
        }
        catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
    }
    private key(itemId: string) { return `codex-async:${this.threadId}:${itemId}`; }
    private answerId(itemId: string) { return `${this.key(itemId)}:answer`; }
    private save(): Promise<void> {
        const data = JSON.stringify({ settled: this.settled, attempts: this.attempts });
        this.writes = this.writes.catch(() => {}).then(async () => {
            await mkdir(dirname(this.file), { recursive: true });
            await writeFile(`${this.file}.tmp`, data);
            await rename(`${this.file}.tmp`, this.file);
        });
        return this.writes;
    }
    private publish(q: Question): void {
        const key = this.key(q.itemId);
        const done = this.settled[key];
        const revision = `${key}:${done?.status ?? 'pending'}`;
        if (this.published.has(revision)) return;
        this.published.add(revision);
        const id = `${key}:start`;
        this.session.sendAgentMessage({ type: 'tool-call', name: 'request_user_input', callId: key, input: q.input, id }, id);
        if (done) {
            const resultId = `${key}:result`;
            this.session.sendAgentMessage({ type: 'tool-call-result', callId: key, output: done, id: resultId }, resultId);
        }
        this.session.updateAgentState(state => {
            const requests = { ...state.requests };
            const entry = { tool: 'request_user_input', toolCallId: key, arguments: q.input,
                createdAt: requests[key]?.createdAt ?? state.completedRequests?.[key]?.createdAt ?? Date.now() };
            if (!done) {
                requests[key] = entry;
                const completedRequests = { ...state.completedRequests }; delete completedRequests[key];
                return { ...state, requests, completedRequests };
            }
            delete requests[key];
            return { ...state, requests, completedRequests: { ...state.completedRequests,
                [key]: { ...entry, ...done, completedAt: Date.now() } } };
        });
    }
    private async finish(q: Question, status: 'approved' | 'canceled' | 'resolved', answers?: AnswerMap): Promise<void> {
        const key = this.key(q.itemId);
        this.settled[key] = { status, ...(answers ? { answers } : {}) };
        await this.save();
        this.pending.delete(key);
        this.publish(q);
    }
    async receive(item: Record<string, unknown>, superseded = false): Promise<void> {
        const input = asyncQuestionsInput(item), itemId = string(item.id);
        if (!input || !itemId) return;
        const key = this.key(itemId);
        if (this.pending.has(key)) return;
        // Old async fallbacks were already shown as prose. Do not append new
        // canceled cards for them at the end of a resumed conversation.
        if (superseded && !['approved', 'resolved'].includes(this.settled[key]?.status ?? '')) return;
        const q: Question = { itemId, input, sending: false };
        if (!this.settled[key]) this.pending.set(key, q);
        this.publish(q);
    }
    async userMessage(item: Record<string, unknown>): Promise<void> {
        const id = string(item.clientId ?? item.clientUserMessageId);
        for (const q of [...this.pending.values()]) {
            if (q.sending) continue;
            // A subsequent free-text response supersedes an optional question;
            // only our correlated native message proves structured submission.
            const ours = id === this.answerId(q.itemId);
            await this.finish(q, ours ? 'resolved' : 'canceled', ours ? this.attempts[this.key(q.itemId)] : undefined);
        }
    }
    async history(thread: Record<string, unknown>): Promise<void> {
        const items = (Array.isArray(thread.turns) ? thread.turns : []).flatMap(t => {
            const items = record(t).items; return Array.isArray(items) ? items.map(record) : [];
        });
        let lastUser = -1;
        items.forEach((item, index) => { if (item.type === 'userMessage') lastUser = index; });
        for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (!asyncQuestionsInput(item)) continue;
            const key = this.key(String(item.id));
            const answered = items.slice(i + 1).some(later => later.type === 'userMessage'
                && string(later.clientId ?? later.clientUserMessageId) === this.answerId(String(item.id)));
            if (answered && !['approved', 'resolved'].includes(this.settled[key]?.status ?? '')) {
                this.settled[key] = { status: 'resolved', ...(this.attempts[key] ? { answers: this.attempts[key] } : {}) };
                await this.save();
            }
            const pending = this.pending.get(key);
            if (pending && i < lastUser && !pending.sending) await this.finish(pending, answered ? 'resolved' : 'canceled', answered ? this.attempts[key] : undefined);
            else await this.receive(item, i < lastUser);
        }
    }
    async answer(reply: Reply): Promise<boolean> {
        const q = this.pending.get(reply.id);
        if (!q) return false;
        if (q.sending) throw new Error('Answer already being submitted');
        if (!reply.approved) { await this.finish(q, 'canceled'); return true; }
        const answers: AnswerMap = {};
        for (const question of q.input!.questions) {
            const value = reply.answers?.[question.id];
            const selected = (Array.isArray(value) ? value : value?.answers)?.filter(a => a.trim());
            if (!selected?.length) throw new Error(`Missing answer: ${question.id}`);
            answers[question.id] = { answers: selected };
        }
        const attempted = this.attempts[reply.id];
        if (attempted && JSON.stringify(attempted) !== JSON.stringify(answers)) {
            throw new Error('A previous answer may already be queued; retry that same answer to confirm delivery');
        }
        q.sending = true;
        try {
            this.attempts[reply.id] = answers;
            await this.save();
            const text = q.input!.questions.map(question => `${question.question}\n${answers[question.id].answers.map(a => a.replace(/^user_note:\s*/, '')).join('\n')}`).join('\n\n');
            await this.submit(this.answerId(q.itemId), text);
            await this.finish(q, 'approved', answers);
        } catch (error) { q.sending = false; throw error; }
        return true;
    }
}
