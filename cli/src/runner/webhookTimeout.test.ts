import { expect, it } from 'vitest';
import { runnerWebhookTimeoutMs } from './webhookTimeout';

it('allows full shared Codex restoration within the Hub spawn budget', () => {
    expect(runnerWebhookTimeoutMs('codex')).toBe(90_000);
    expect(runnerWebhookTimeoutMs('claude')).toBe(15_000);
    expect(runnerWebhookTimeoutMs(undefined)).toBe(15_000);
    expect(runnerWebhookTimeoutMs('codex', '45000')).toBe(45_000);
    for (const bad of ['0', '-1', 'NaN', 'Infinity']) expect(runnerWebhookTimeoutMs('codex', bad)).toBe(90_000);
});
