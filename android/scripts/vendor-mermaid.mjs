// Run after the repo's frozen dependency install. Android builds use the
// checked-in standalone distribution, including its bundled license notices.
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'

const version = '11.14.0'
const input = new URL('../../web/node_modules/mermaid/', import.meta.url)
const output = new URL('../app/src/main/assets/mermaid/', import.meta.url)
const pkg = JSON.parse(await readFile(new URL('package.json', input), 'utf8'))
if (pkg.version !== version) throw new Error(`Expected Mermaid ${version}, found ${pkg.version}`)
const script = await readFile(new URL('dist/mermaid.min.js', input))
if (script.includes('import(')) throw new Error('Expected a standalone Mermaid distribution')
await mkdir(output, { recursive: true })
await writeFile(new URL('mermaid.min.js', output), script)
await writeFile(new URL('LICENSE', output), await readFile(new URL('LICENSE', input)))
await writeFile(new URL('vendor-info.json', output), JSON.stringify({
    package: 'mermaid', version,
    source: `https://registry.npmjs.org/mermaid/-/mermaid-${version}.tgz`,
    sha256: createHash('sha256').update(script).digest('hex'),
}, null, 2) + '\n')
console.log(`Vendored Mermaid ${version}: ${script.length} bytes`)
