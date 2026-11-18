import { useEffect, useRef, useState } from 'react'
import type { NativeSessionSummary } from '@hapi/protocol'
import type { ApiClient } from '@/api/client'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { useTranslation } from '@/lib/use-translation'

export function NativeSessionImport(props: {
    api: ApiClient; machineId: string | null; flavor: 'opencode' | 'dsh'; directory: string;
    disabled: boolean; onSuccess: (id: string) => void
}) {
    const { t } = useTranslation()
    const [open, setOpen] = useState(false)
    const [rows, setRows] = useState<NativeSessionSummary[]>([])
    const [loading, setLoading] = useState(false)
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [query, setQuery] = useState('')
    const [directory, setDirectory] = useState('')
    const [selected, setSelected] = useState('')
    const name = props.flavor === 'dsh' ? 'DeepSeek Harness' : 'OpenCode'
    const generation = useRef(0)
    useEffect(() => { generation.current++; setBusy(false); return () => { generation.current++ } }, [props.machineId, props.flavor, props.api])

    useEffect(() => {
        if (!open || !props.machineId) return
        let cancelled = false
        setLoading(true); setError(null); setRows([]); setSelected(''); setQuery('')
        void props.api.getNativeSessions(props.machineId, props.flavor).then(response => {
            if (cancelled) return
            if (!response.success) throw new Error(response.error ?? 'Native catalog unavailable')
            setRows(response.sessions)
            const path = response.sessions.find(s => s.cwd === props.directory || s.basePath === props.directory)
            setDirectory(path ? (path.basePath ?? path.cwd) : '')
        }).catch(error => { if (!cancelled) setError(error.message) })
            .finally(() => { if (!cancelled) setLoading(false) })
        return () => { cancelled = true }
    }, [open, props.machineId, props.flavor, props.api, props.directory])

    const filtered = rows.filter(s => (!directory || (s.basePath ?? s.cwd) === directory)
        && `${s.title} ${s.cwd} ${s.id}`.toLowerCase().includes(query.toLowerCase()))

    async function confirm() {
        if (!selected || !props.machineId || busy) return
        const token = generation.current
        setBusy(true); setError(null)
        try {
            const result = await props.api.importNativeSession(props.machineId, props.flavor, selected)
            if (!result.success || !result.hapiSessionIds?.[0]) throw new Error(result.error ?? 'Native import failed')
            const id = await props.api.resumeSession(result.hapiSessionIds[0])
            if (token !== generation.current) return
            setOpen(false); props.onSuccess(id)
        } catch (error) { if (token === generation.current) setError(error instanceof Error ? error.message : String(error)) }
        finally { if (token === generation.current) setBusy(false) }
    }

    return <div className="px-3 py-2">
        <Button type="button" variant="outline" disabled={props.disabled || !props.machineId} onClick={() => setOpen(true)}>
            {t('nativeImport.title', { agent: name })}
        </Button>
        <Dialog open={open} onOpenChange={value => { if (!busy) setOpen(value) }}>
            <DialogContent className="max-w-xl">
                <DialogHeader>
                    <DialogTitle>{t('nativeImport.title', { agent: name })}</DialogTitle>
                    <DialogDescription>{t('nativeImport.description')}</DialogDescription>
                </DialogHeader>
                <div className="flex gap-2">
                    <input type="search" aria-label={t('nativeImport.search')} placeholder={t('nativeImport.search')}
                        value={query} disabled={busy} onChange={e => setQuery(e.target.value)}
                        className="min-w-0 flex-1 rounded border border-[var(--app-border)] bg-[var(--app-bg)] p-2" />
                    <select aria-label={t('nativeImport.directory')} value={directory} disabled={busy}
                        onChange={e => { setDirectory(e.target.value); setSelected('') }}
                        className="min-w-0 max-w-[50%] rounded border border-[var(--app-border)] bg-[var(--app-bg)] p-2">
                        <option value="">{t('nativeImport.allDirectories')}</option>
                        {[...new Set(rows.map(s => s.basePath ?? s.cwd))].sort().map(path => <option key={path} value={path}>{path}</option>)}
                    </select>
                </div>
                {error ? <p role="alert" className="text-sm text-red-600">{error}</p> : null}
                <div className="max-h-[50vh] space-y-1 overflow-y-auto">
                    {loading ? <p>{t('loading')}</p> : filtered.length === 0 ? <p>{t('nativeImport.empty')}</p>
                        : filtered.map(row => <button key={row.id} type="button" disabled={busy} aria-pressed={selected === row.id}
                            onClick={() => setSelected(row.id)}
                            className={`w-full rounded border p-3 text-left ${selected === row.id ? 'border-[var(--app-link)] bg-[var(--app-subtle-bg)]' : 'border-[var(--app-border)]'}`}>
                            <div className="truncate font-medium">{row.title}</div>
                            <div className="truncate text-xs text-[var(--app-hint)]">{row.cwd}</div>
                            <div className="text-xs text-[var(--app-hint)]">{new Date(row.modifiedAt).toLocaleString()}{row.profile ? ` · ${row.profile}` : ''}</div>
                        </button>)}
                </div>
                <div className="flex justify-end gap-2">
                    <Button type="button" variant="secondary" disabled={busy} onClick={() => setOpen(false)}>{t('button.cancel')}</Button>
                    <Button type="button" disabled={!selected || busy || loading} onClick={() => void confirm()}>{busy ? t('loading') : t('nativeImport.open')}</Button>
                </div>
            </DialogContent>
        </Dialog>
    </div>
}
