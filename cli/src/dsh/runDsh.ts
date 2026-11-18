import { runSharedNativeAgent } from '@/native/run'

export async function runDsh(opts: {
    startedBy?: 'runner' | 'terminal'
    startingMode?: 'remote'
    existingSessionId?: string
    resumeSessionId?: string
    reservedSessionId?: string
    workingDirectory?: string
} = {}): Promise<void> {
    return runSharedNativeAgent('dsh', opts)
}
