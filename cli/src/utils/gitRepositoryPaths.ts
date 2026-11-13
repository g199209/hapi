import { execFile } from 'node:child_process'
import { realpath } from 'node:fs/promises'
import { resolve, relative, isAbsolute, sep } from 'node:path'
import { promisify } from 'node:util'

const exec = promisify(execFile)

export type GitRepositoryPath = { basePath: string; worktreePath: string; linked: boolean }

/** Per-request Git identity, shared across all cwd checks on one machine.
 * Use the worktree registry, including prunable entries; names and locations
 * of Codex/HAPI/custom worktrees do not imply repository identity. */
export async function resolveGitRepositoryPaths(paths: string[]): Promise<Map<string, GitRepositoryPath>> {
    const repositories = new Map<string, Promise<string[]>>()
    const result = new Map<string, GitRepositoryPath>()
    const unique = [...new Set(paths.map(path => path.trim()).filter(Boolean))]
    const canonical = async (path: string) => realpath(path).catch(() => resolve(path))
    async function git(cwd: string, args: string[]) {
        return (await exec('git', args, { cwd, timeout: 5_000, maxBuffer: 1024 * 1024, windowsHide: true,
            env: { ...process.env, GIT_OPTIONAL_LOCKS: '0' } })).stdout
    }
    let index = 0
    await Promise.all(Array.from({ length: Math.min(4, unique.length) }, async () => {
        while (index < unique.length) {
            const path = unique[index++]
            try {
                const common = await canonical((await git(path, ['rev-parse', '--path-format=absolute', '--git-common-dir'])).trim())
                let worktrees = repositories.get(common)
                if (!worktrees) {
                    worktrees = git(path, ['worktree', 'list', '--porcelain', '-z']).then(async output =>
                        Promise.all(output.split('\0').filter(part => part.startsWith('worktree '))
                            .map(part => canonical(part.slice('worktree '.length)))))
                    repositories.set(common, worktrees)
                }
                await worktrees
            } catch { /* Non-repositories and missing historical cwd stay ungrouped. */ }
        }
    }))
    const registry: GitRepositoryPath[] = []
    for (const promise of repositories.values()) {
        const roots = await promise.catch(() => [])
        for (const root of roots) registry.push({ basePath: roots[0], worktreePath: root, linked: root !== roots[0] })
    }
    // Longest actual worktree root wins for subdirectories and nested repos.
    registry.sort((a, b) => b.worktreePath.length - a.worktreePath.length)
    for (const path of unique) {
        const target = await canonical(path)
        const match = registry.find(entry => {
            const rest = relative(entry.worktreePath, target)
            return rest === '' || (rest !== '..' && !rest.startsWith(`..${sep}`) && !isAbsolute(rest))
        })
        if (match) result.set(path, match)
    }
    return result
}
