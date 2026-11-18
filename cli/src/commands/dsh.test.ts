import { describe, expect, it } from 'vitest'
import { parseDshCommandOptions } from './dsh'

describe('parseDshCommandOptions', () => {
    it('forces remote mode and accepts an existing HAPI row', () => {
        expect(parseDshCommandOptions([
            '--hapi-starting-mode', 'remote',
            '--existing-session-id', 'hapi-session'
        ])).toEqual({
            startingMode: 'remote',
            existingSessionId: 'hapi-session'
        })
    })

    it('accepts a native resume target without overriding Host settings', () => {
        expect(parseDshCommandOptions(['--resume', 'native-id'])).toMatchObject({resumeSessionId:'native-id',startingMode:'remote'})
    })

    it('leaves model and permission controls with the native Host', () => {
        expect(() => parseDshCommandOptions(['--model', 'deepseek-v4-pro']))
            .toThrow('configured by the native Host')
        expect(() => parseDshCommandOptions(['--yolo']))
            .toThrow('permission policy')
    })
})
