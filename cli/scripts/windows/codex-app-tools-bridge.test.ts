import { describe, expect, it } from 'vitest';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createServer, createConnection, type Server } from 'node:net';
import { spawn } from 'node:child_process';
import { createPublicKey, generateKeyPairSync, sign, verify } from 'node:crypto';

const delay = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
const frame = (message: unknown) => {
    const data = Buffer.from(JSON.stringify(message)), header = Buffer.alloc(4);
    header.writeUInt32LE(data.length); return Buffer.concat([header, data]);
};
function request(endpoint: string): Promise<Record<string, unknown>> {
    return new Promise((resolve, reject) => {
        const socket = createConnection(endpoint); let received = Buffer.alloc(0);
        const timeout = setTimeout(() => { socket.destroy(); reject(new Error('timeout')); }, 3000);
        socket.on('connect', () => socket.write(frame({ id: 1, method: 'taskThread/read', params: {} })));
        socket.on('data', data => {
            received = Buffer.concat([received, data]);
            if (received.length >= 4 && received.length >= received.readUInt32LE(0) + 4) {
                resolve(JSON.parse(received.subarray(4).toString())); socket.destroy();
            }
        });
        socket.on('error', reject);
        socket.on('close', () => { clearTimeout(timeout); reject(new Error('closed')); });
    });
}

describe('persistent Windows App tools endpoint', () => {
    it('preserves ordinary replies, verifies signed replies, follows desktop restarts, and rejects forged signatures', async () => {
        const root = await mkdtemp(join(tmpdir(), 'hapi-app-tools-'));
        const endpoint = join(root, 'bridge.sock'), desktopPath = join(root, 'desktop.json');
        const child = spawn(process.execPath, [join(import.meta.dirname, 'codex-app-tools-bridge.cjs'), root, endpoint], { stdio: 'pipe' });
        child.stdout.resume(); child.stderr.resume(); const servers: Server[] = [];
        const installDesktop = async (name: string, signed: boolean, forged = false) => {
            const pair = generateKeyPairSync('ed25519'), pipePath = join(root, name + '.sock');
            const server = createServer(socket => {
                socket.on('error', () => {});
                socket.once('data', () => {
                    const payload = JSON.stringify({ domain: 'codex.taskThread.read.v1', thread: { id: name } });
                    const result = signed ? { payload, signature: sign(null, Buffer.from(forged ? 'other' : payload), pair.privateKey).toString('base64') }
                        : { tools: [{ name: 'ordinary' }], opaque: { payload: 'third-party', signature: 'opaque' } };
                    const bytes = frame({ id: 1, result });
                    socket.write(bytes.subarray(0, 7)); setTimeout(() => socket.end(bytes.subarray(7)), 5);
                });
            });
            servers.push(server); await new Promise<void>(resolve => server.listen(pipePath, resolve));
            await writeFile(desktopPath, JSON.stringify({ pipePath, verifyingIdentity: pair.publicKey.export({ type: 'spki', format: 'der' }).toString('base64') }));
            await delay(650); return pair;
        };
        try {
            for (let i = 0; i < 100; i++) {
                try { await readFile(join(root, 'app-tools-ready.json')); break; } catch { await delay(20); }
            }
            const publicKey = await readFile(join(root, 'app-tools-public.txt'), 'utf8');
            const key = createPublicKey({ key: Buffer.from(publicKey, 'base64'), type: 'spki', format: 'der' });
            await expect(request(endpoint)).rejects.toThrow();
            await installDesktop('first', false);
            expect(await request(endpoint)).toEqual({ id: 1, result: { tools: [{ name: 'ordinary' }], opaque: { payload: 'third-party', signature: 'opaque' } } });
            for (const name of ['second', 'third']) {
                const pair = await installDesktop(name, true);
                const response = await request(endpoint), result = response.result as { payload: string; signature: string };
                expect(JSON.parse(result.payload).thread.id).toBe(name);
                expect(verify(null, Buffer.from(result.payload), key, Buffer.from(result.signature, 'base64'))).toBe(true);
                expect(verify(null, Buffer.from(result.payload), pair.publicKey, Buffer.from(result.signature, 'base64'))).toBe(false);
                expect(await readFile(join(root, 'app-tools-public.txt'), 'utf8')).toBe(publicKey);
            }
            await installDesktop('forged', true, true);
            await expect(request(endpoint)).rejects.toThrow('closed');
            expect(child.exitCode).toBeNull();
        } finally {
            child.kill(); await new Promise<void>(resolve => child.once('exit', () => resolve()));
            await Promise.all(servers.map(server => new Promise<void>(resolve => server.close(() => resolve()))));
            await rm(root, { recursive: true, force: true });
        }
    }, 15_000);
});
