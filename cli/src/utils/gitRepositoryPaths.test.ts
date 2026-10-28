import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync, realpathSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { resolveGitRepositoryPaths } from './gitRepositoryPaths'

const roots: string[] = []
afterEach(() => { for (const path of roots.splice(0)) rmSync(path, { recursive: true, force: true }) })
function fixture() {
    const root = mkdtempSync(join(tmpdir(), 'hapi-git-paths-')); roots.push(root)
    const repo = join(root, 'main repo'); mkdirSync(repo)
    const git = (cwd: string, args: string[]) => execFileSync('git', args, { cwd, stdio: 'pipe', env: {
        ...process.env, GIT_AUTHOR_NAME: 'Test', GIT_AUTHOR_EMAIL: 'test@example.com',
        GIT_COMMITTER_NAME: 'Test', GIT_COMMITTER_EMAIL: 'test@example.com'
    } })
    git(repo, ['init']); git(repo, ['commit', '--allow-empty', '-m', 'initial'])
    return { root, repo: realpathSync(repo), git }
}
describe('Git repository directory identity', () => {
    it('associates arbitrary linked worktree locations and their subdirectories with the primary directory', async () => {
        const { root, repo, git } = fixture()
        const tree = join(root, 'unrelated name'); git(repo, ['worktree', 'add', '--detach', tree])
        const sub = join(tree, 'nested'); mkdirSync(sub)
        const alias = join(root, 'alias'); symlinkSync(tree, alias, 'dir')
        const result = await resolveGitRepositoryPaths([repo, tree, sub, alias, tree])
        expect(result.get(repo)).toEqual({ basePath: repo, worktreePath: repo, linked: false })
        for (const path of [tree, sub, alias]) expect(result.get(path)).toEqual({ basePath: repo, worktreePath: tree, linked: true })
    })
    it('uses registered prunable worktrees without guessing missing lookalike directories', async () => {
        const { root, repo, git } = fixture()
        const tree = join(root, 'removed'); git(repo, ['worktree', 'add', '--detach', tree]); rmSync(tree, { recursive: true })
        const lookalike = tree + '-other'
        const result = await resolveGitRepositoryPaths([repo, tree, join(tree, 'sub'), lookalike])
        expect(result.get(tree)?.basePath).toBe(repo)
        expect(result.get(join(tree, 'sub'))?.basePath).toBe(repo)
        expect(result.has(lookalike)).toBe(false)
    })
    it('keeps separate clones and nested repositories distinct even when their names match', async () => {
        const { root, repo, git } = fixture()
        const clone = join(root, 'other', 'main repo'); mkdirSync(join(root, 'other'))
        git(root, ['clone', repo, clone])
        const nested = join(repo, 'nested'); mkdirSync(nested); git(nested, ['init']); git(nested, ['commit', '--allow-empty', '-m', 'nested'])
        const result = await resolveGitRepositoryPaths([repo, clone, nested, root])
        expect(result.get(clone)?.basePath).toBe(clone)
        expect(result.get(nested)?.basePath).toBe(nested)
        expect(result.has(root)).toBe(false)
    })
})
