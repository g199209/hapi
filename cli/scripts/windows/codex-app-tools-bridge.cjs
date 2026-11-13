// Stable local endpoint for the desktop-owned App tools pipe. The desktop's
// signed task responses are verified, then re-signed for the persistent engine.
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const [root, endpoint] = process.argv.slice(2);
if (!root || !endpoint) throw Error('Usage: node codex-app-tools-bridge.cjs NODE_DIRECTORY PIPE');
fs.mkdirSync(root, { recursive: true });
const keyPath = path.join(root, 'app-tools-private.pem');
if (!fs.existsSync(keyPath)) {
    const { privateKey } = crypto.generateKeyPairSync('ed25519');
    fs.writeFileSync(keyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600, flag: 'wx' });
}
const key = crypto.createPrivateKey(fs.readFileSync(keyPath));
const publicKey = crypto.createPublicKey(key).export({ type: 'spki', format: 'der' }).toString('base64');
fs.writeFileSync(path.join(root, 'app-tools-public.txt'), publicKey);
const desktopPath = path.join(root, 'desktop.json');
let desktop = null;
const connections = new Set();
function refresh() {
    let next = null;
    try { next = JSON.parse(fs.readFileSync(desktopPath, 'utf8').replace(/^\uFEFF/, '')); } catch { /* App may be closed. */ }
    if (JSON.stringify(next) === JSON.stringify(desktop)) return;
    desktop = next;
    for (const socket of connections) socket.destroy();
}
refresh();
fs.watchFile(desktopPath, { interval: 500 }, refresh);
function translate(value, verifyingKey) {
    if (!value || typeof value !== 'object') return value;
    if (typeof value.payload === 'string' && typeof value.signature === 'string') {
        const bytes = Buffer.from(value.payload);
        if (!crypto.verify(null, bytes, verifyingKey, Buffer.from(value.signature, 'base64'))) {
            throw Error('Desktop task response signature verification failed');
        }
        return { ...value, signature: crypto.sign(null, bytes, key).toString('base64') };
    }
    return value;
}
const server = net.createServer(downstream => {
    refresh();
    if (!desktop?.pipePath || !desktop?.verifyingIdentity) { downstream.destroy(); return; }
    let verifyingKey;
    try { verifyingKey = crypto.createPublicKey({ key: Buffer.from(desktop.verifyingIdentity, 'base64'), type: 'spki', format: 'der' }); }
    catch { downstream.destroy(); return; }
    const upstream = net.createConnection(desktop.pipePath);
    connections.add(downstream);
    const close = () => { connections.delete(downstream); downstream.destroy(); upstream.destroy(); };
    downstream.on('error', close); upstream.on('error', close);
    downstream.on('close', close); upstream.on('close', close);
    downstream.pipe(upstream);
    let buffered = Buffer.alloc(0);
    upstream.on('data', data => {
        try {
            buffered = Buffer.concat([buffered, data]);
            while (buffered.length >= 4) {
                const length = buffered.readUInt32LE(0);
                if (!length || length > 64 * 1024 * 1024) throw Error('Invalid App tools frame length');
                if (buffered.length < 4 + length) break;
                const message = JSON.parse(buffered.subarray(4, 4 + length).toString('utf8'));
                buffered = buffered.subarray(4 + length);
                // Only the native RPC result is a task proof. Tool outputs
                // can themselves contain opaque third-party signed objects.
                if ('result' in message) message.result = translate(message.result, verifyingKey);
                const bytes = Buffer.from(JSON.stringify(message));
                const header = Buffer.alloc(4); header.writeUInt32LE(bytes.length);
                if (!downstream.write(Buffer.concat([header, bytes]))) {
                    upstream.pause(); downstream.once('drain', () => upstream.resume());
                }
            }
        } catch (error) { console.error(error.message); close(); }
    });
});
server.on('error', error => { console.error(error.message); process.exit(1); });
server.listen(endpoint, () => fs.writeFileSync(path.join(root, 'app-tools-ready.json'), JSON.stringify({ pid: process.pid, endpoint, publicKey })));
