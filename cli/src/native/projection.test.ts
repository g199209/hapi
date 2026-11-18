import { describe, expect, it } from 'vitest'
import { AGENT_MESSAGE_PAYLOAD_TYPE } from '@hapi/protocol'
import { DshLiveBlocks, projectDshEvent, projectOpencodeMessage, canonicalMessages } from './projection'

describe('native conversation projection', () => {
    it('keeps native execution order when queued input has an earlier creation timestamp and uses integral cursors', () => {
        const view=canonicalMessages([
            {id:'previous-reply',at:200,content:{}},
            {id:'queued-user',at:100,content:{}},
            {id:'next-reply',at:200,content:{}},
        ])
        expect(view.map(m=>m.id)).toEqual(['previous-reply','queued-user','next-reply'])
        expect(view.map(m=>m.at)).toEqual([200,201,202])
        expect(view.map(m=>m.createdAt)).toEqual([200,100,200])
    })
    it('uses the shared chat envelope and a stable identity for OpenCode partial/final text', () => {
        const message = { id: 'msg_native', type: 'assistant', time: { created: 100 }, content: [{ type: 'text', text: 'partial' }] }
        const live = projectOpencodeMessage(message)[0]
        const final = projectOpencodeMessage({ ...message, time: { created: 100, completed: 200 }, content: [{ type: 'text', text: 'final' }] })[0]
        expect(live.id).toBe(final.id)
        expect(final.content).toMatchObject({ role: 'agent', content: { type: AGENT_MESSAGE_PAYLOAD_TYPE, data: { type: 'message', message: 'final' } } })
    })
    it('correlates HAPI submission IDs with native user records', () => {
        expect(projectOpencodeMessage({ id: 'msg_hapi_input', type: 'user', time: { created: 10 }, text: 'hello' })[0].inputLocalId).toBe('input')
        expect(projectDshEvent({ type: 'user/message', seq: 3, time: 10, data: { source: { kind: 'user', rpcId: 'hapi:input' }, content: [{ type: 'text', text: 'hello' }] } })[0].inputLocalId).toBe('input')
    })
    it('filters DSH job/internal user deliveries and gives tool call/result their native correlation', () => {
        expect(projectDshEvent({ type: 'user/message', data: { source: { kind: 'tool-jobs' }, content: [{ type: 'text', text: 'internal' }] } })).toEqual([])
        expect(projectDshEvent({ type: 'tool/call', seq: 1, time: 10, data: { callId: 'call', name: 'bash', arguments: '{"command":"pwd"}' } })[0].content)
            .toMatchObject({ content: { data: { type: 'tool-call', callId: 'call', input: { command: 'pwd' } } } })
        expect(projectDshEvent({ type: 'tool/result', seq: 2, time: 20, data: { message: { toolCallId: 'call', content: [{ type: 'text', text: '/tmp' }] } } })[0].content)
            .toMatchObject({ content: { data: { type: 'tool-call-result', callId: 'call', output: '/tmp' } } })
    })
    it('restores compact DSH streams and replaces partials with the final block', () => {
        const blocks = new DshLiveBlocks()
        blocks.seed([{ type: 'text-chunks', index: 0, texts: ['one', 'two'] }])
        expect(blocks.values()).toEqual([{ type: 'text', text: 'onetwo' }])
        blocks.apply({ type: 'block-end', index: 0, block: { type: 'text', text: 'complete' } })
        expect(blocks.values()).toEqual([{ type: 'text', text: 'complete' }])
    })
    it('maps native uncached/cache token counters to inclusive HAPI usage without double counting', () => {
        const records=projectOpencodeMessage({id:'msg',type:'assistant',time:{created:1,completed:2},content:[],
            model:{providerID:'provider',id:'model'},tokens:{input:80,output:10,reasoning:5,cache:{read:20,write:10}}})
        expect(records[0].content).toMatchObject({content:{data:{type:'token_count',info:{total:{inputTokens:110,outputTokens:15,cachedInputTokens:20}}}}})
    })
    it('shows a durable native failure instead of silently becoming idle without a reply', () => {
        expect(projectDshEvent({type:'turn/end',seq:4,time:20,data:{reason:{kind:'error',error:{message:'Provider unavailable'}}}})[0].content)
            .toMatchObject({content:{data:{type:'error',message:'Provider unavailable'}}})
    })
})
