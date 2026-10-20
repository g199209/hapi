/** Shared Codex reads paginated history before ready; stay below the Hub's 120s spawn RPC. */
export function runnerWebhookTimeoutMs(agent: string | undefined, configured?: string): number {
    const value = Number(configured);
    return Number.isFinite(value) && value > 0 ? value : agent === 'codex' ? 90_000 : 15_000;
}
