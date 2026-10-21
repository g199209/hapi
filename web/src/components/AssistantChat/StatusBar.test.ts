import { describe, expect, it } from 'vitest'
import {
    formatCompactContextUsageLabel,
    formatContextUsageLabel,
    getContextWarning,
    getConnectionStatus,
    getContextUsageDetails,
    shouldShowCodexFastBadge
} from './StatusBar'

describe('context warning colors', () => {
    it('keeps usage below 70% muted', () => {
        expect(getContextWarning(69, 100).color).toBe('text-[var(--app-hint)]')
    })

    it('shows a warning from 70% through below 90%', () => {
        expect(getContextWarning(70, 100).color).toBe('text-amber-500')
        expect(getContextWarning(89, 100).color).toBe('text-amber-500')
    })

    it('shows danger at 90% and above', () => {
        expect(getContextWarning(90, 100).color).toBe('text-red-500')
        expect(getContextWarning(95, 100).color).toBe('text-red-500')
    })
})

describe('context usage labels', () => {
    it('keeps the desktop label compact and expresses used capacity', () => {
        expect(formatContextUsageLabel(90_000, 258_000)).toBe('35% · 90k / 258k')
    })

    it('uses the compact parenthesized mobile label with a fixed English suffix', () => {
        expect(formatCompactContextUsageLabel(186_000, 262_000)).toBe('ctx 262k (29% left)')
    })

    it('orders cache, used, and remaining metrics for the desktop details', () => {
        expect(getContextUsageDetails(90_000, 258_000, 86_000)).toEqual({
            cacheRead: '86k',
            used: '90k',
            usedPercentage: 35,
            remaining: '168k',
            remainingPercentage: 65
        })
    })

    it('keeps external and detailed percentages complementary at rounding midpoints', () => {
        expect(formatContextUsageLabel(69, 200)).toBe('35% · 69 / 200')
        expect(formatCompactContextUsageLabel(69, 200)).toBe('ctx 200 (65% left)')
        expect(getContextUsageDetails(69, 200, 0)).toMatchObject({
            usedPercentage: 35,
            remainingPercentage: 65
        })
    })
})

describe('shouldShowCodexFastBadge', () => {
    it('uses only the effective service tier', () => {
        expect(shouldShowCodexFastBadge('codex', undefined)).toBe(false)
        expect(shouldShowCodexFastBadge('codex', 'standard')).toBe(false)
        expect(shouldShowCodexFastBadge('codex', 'fast')).toBe(true)
        expect(shouldShowCodexFastBadge('codex', 'priority')).toBe(true)
        expect(shouldShowCodexFastBadge('claude', 'fast')).toBe(false)
    })
})


describe('Codex compaction connection status', () => {
    const compacting = { codexCompaction: { turnId: 'turn', itemId: 'compact' } }
    const t = (key: string) => key
    it('shows live compaction in the existing status before generic thinking', () => {
        expect(getConnectionStatus(true, true, compacting, undefined, 0, t))
            .toMatchObject({ text: 'misc.compacting', isPulsing: true })
        expect(getConnectionStatus(true, false, compacting, undefined, 2, t).text).toBe('misc.compacting')
    })
    it('keeps offline and pending permission status ahead of compaction', () => {
        expect(getConnectionStatus(false, true, compacting, undefined, 0, t).text).toBe('misc.offline')
        expect(getConnectionStatus(true, true, { ...compacting, requests: {
            ask: { tool: 'request_user_input', arguments: {}, createdAt: 0 }
        } }, undefined, 0, t).text).toBe('misc.permissionRequired')
    })
    it('withdraws compaction when the native state clears', () => {
        expect(getConnectionStatus(true, false, { codexCompaction: null }, undefined, 0, t).text).toBe('misc.online')
    })
})
