import { execFile } from 'node:child_process';
import { statSync } from 'node:fs';
import { promisify } from 'node:util';
import { getAgentLaunchCommand, resolveExecutable } from '@/agent/agentLaunchCommand';

export function opencodeExecutable(): string {
    const executable = resolveExecutable(getAgentLaunchCommand('opencode'));
    if (!executable) throw new Error('OpenCode executable was not found');
    return executable;
}

const exec = promisify(execFile);
const transports = new Map<string, Promise<boolean>>();

/** v2 ACP connects to OpenCode's service and no longer accepts v1 HTTP flags. */
export async function opencodeUsesEmbeddedHttpApi(): Promise<boolean> {
    const executable = opencodeExecutable();
    const key = `${executable}:${statSync(executable).mtimeMs}`;
    let result = transports.get(key);
    if (!result) {
        result = exec(executable, ['--version'], { windowsHide: true, timeout: 10_000 }).then(({ stdout }) => {
            const version = /(?:opencode\s+v?)?(\d+)\.\d+\.\d+/.exec(stdout);
            if (!version) throw new Error('Cannot determine OpenCode ACP version');
            return Number(version[1]) < 2;
        });
        transports.set(key, result);
        void result.catch(() => transports.delete(key));
    }
    return result;
}


export async function readOpencodeV2Models(cwd: string | null): Promise<unknown> {
    const query = cwd ? '?' + new URLSearchParams({ 'location[directory]': cwd }) : '';
    const { stdout } = await exec(opencodeExecutable(), ['api', 'GET', '/api/model' + query], { windowsHide: true, timeout: 30_000, maxBuffer: 8 * 1024 * 1024, ...(cwd ? { cwd } : {}) });
    return JSON.parse(stdout);
}
