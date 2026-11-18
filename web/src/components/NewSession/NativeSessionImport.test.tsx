import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient } from '@/api/client'
import { NativeSessionImport } from './NativeSessionImport'

vi.mock('@/lib/use-translation', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))

const rows = [
    { id: 'main', title: 'Main history', cwd: '/base', basePath: '/base', modifiedAt: 100 },
    { id: 'tree', title: 'Worktree history', cwd: '/trees/review', basePath: '/base', modifiedAt: 200 },
    { id: 'other', title: 'Other project', cwd: '/elsewhere', modifiedAt: 300 },
]

describe('native history import', () => {
    it.each(['opencode', 'dsh'] as const)('imports %s from the selected machine and includes its linked worktrees', async flavor => {
        const api = {
            getNativeSessions: vi.fn(async () => ({ success: true, sessions: rows })),
            importNativeSession: vi.fn(async () => ({ success: true, hapiSessionIds: ['existing-binding'] })),
            resumeSession: vi.fn(async () => 'resumed-binding'),
        }
        const onSuccess = vi.fn()
        render(<NativeSessionImport api={api as unknown as ApiClient} machineId="machine" flavor={flavor}
            directory="/base" disabled={false} onSuccess={onSuccess} />)
        fireEvent.click(screen.getByRole('button', { name: 'nativeImport.title' }))
        await screen.findByText('Worktree history')
        expect(screen.queryByText('Other project')).toBeNull()
        fireEvent.click(screen.getByText('Worktree history'))
        fireEvent.click(screen.getByRole('button', { name: 'nativeImport.open' }))
        await waitFor(() => expect(onSuccess).toHaveBeenCalledWith('resumed-binding'))
        expect(api.getNativeSessions).toHaveBeenCalledWith('machine', flavor)
        expect(api.importNativeSession).toHaveBeenCalledWith('machine', flavor, 'tree')
        expect(api.resumeSession).toHaveBeenCalledWith('existing-binding')
    })

    it('does not publish a stale catalog after the selected machine changes', async () => {
        let complete!: (value: unknown) => void
        const api = { getNativeSessions: vi.fn()
            .mockImplementationOnce(() => new Promise(resolve => { complete = resolve }))
            .mockResolvedValue({ success: true, sessions: [rows[2]] }) }
        const props = { api: api as unknown as ApiClient, flavor: 'dsh' as const, directory: '', disabled: false, onSuccess: vi.fn() }
        const view = render(<NativeSessionImport {...props} machineId="old" />)
        fireEvent.click(screen.getByRole('button', { name: 'nativeImport.title' }))
        await waitFor(() => expect(api.getNativeSessions).toHaveBeenCalledWith('old', 'dsh'))
        view.rerender(<NativeSessionImport {...props} machineId="new" />)
        await screen.findByText('Other project')
        complete({ success: true, sessions: rows.slice(0, 2) })
        await waitFor(() => expect(screen.queryByText('Worktree history')).toBeNull())
    })
})
