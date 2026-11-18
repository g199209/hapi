import type { NativeMessage } from '@hapi/protocol'
import { agentContent, userContent, array, object, text } from './types'
import { convertAgentMessage } from '@/agent/messageConverter'

/** API/log order is authoritative. Millisecond positions must remain integral
 * for native clients' cursor types, independently of human origin timestamps. */
export function canonicalMessages(messages: NativeMessage[]): NativeMessage[] {
    let previous = -1
    return messages.map(message => {
        const origin = message.createdAt ?? Math.max(0, Math.floor(message.at))
        const at = Math.max(origin, previous + 1)
        previous = at
        return { ...message, at, createdAt: origin }
    })
}

function usageMessage(id: string, at: number, usage: any, model?: string): NativeMessage[] {
    if (!usage || typeof usage.inputTokens !== 'number' || typeof usage.outputTokens !== 'number') return []
    return [{id,at,content:agentContent(convertAgentMessage({type:'usage',...usage},model))}]
}

function input(value: unknown): unknown {
    if (typeof value !== 'string') return value ?? {}
    try { return JSON.parse(value) } catch { return { command: value } }
}
export function contentText(parts: unknown): string {
    return array(parts).map(p => p.type === 'text' ? text(p.text) : p.type === 'image' ? '[Image]' : '').filter(Boolean).join('\n')
}
export function nativeBlocks(prefix: string, blocks: unknown, at: number, live = false): NativeMessage[] {
    return array(blocks).flatMap((block, index): NativeMessage[] => {
        const id = `${prefix}:${index}`
        let body: object
        if (block.type === 'text') body = { type: 'message', message: text(block.text), id, streamSnapshot: live }
        else if (block.type === 'reasoning') body = { type: 'reasoning', message: text(block.text), id, live }
        else if (block.type === 'tool-call') return [] // The durable tool/call event owns its lifecycle.
        else return []
        if (!text(block.text)) return []
        return [{ id, at, content: agentContent(body) }]
    })
}

export function projectDshEvent(event: any, nativeSessionId?: string): NativeMessage[] {
    const d = object(event.data), id = `event:${event.seq}`, at = Number(event.time) || 0
    if (event.type === 'user/message') {
        // Internal delivery/repair messages are not human input.
        if (object(d.source).kind && d.source.kind !== 'user') return []
        const value = contentText(d.content)
        const requestId = text(object(d.source).rpcId)
        return value ? [{ id, at, content: userContent(value), ...(requestId.startsWith('hapi:')
            ? { inputLocalId: requestId.slice(5) }
            : nativeSessionId && d.id ? { inputLocalId: `dsh:${nativeSessionId}:queue:${d.id}` } : {}) }] : []
    }
    if (event.type === 'assistant/message' || event.type === 'assistant/attempt') {
        const blocks = nativeBlocks(event.type === 'assistant/message' ? `assistant:${d.turn}:${d.step}` : id, object(d.message).content, at)
        const u = object(d.usage), source = object(object(d.message).source)
        return [...blocks,...usageMessage(`${id}:usage`,at,typeof u.inputTokens==='number'?{
            inputTokens:typeof u.totalTokens==='number'&&u.totalTokens>=u.outputTokens?Math.max(0,u.totalTokens-u.outputTokens-(u.cacheReadTokens??0)-(u.cacheWriteTokens??0)):u.inputTokens,outputTokens:u.outputTokens,
            cacheReadTokens:u.cacheReadTokens,cacheCreationTokens:u.cacheWriteTokens,totalTokens:u.totalTokens,thoughtTokens:u.reasoningTokens,
        }:undefined,source.provider&&source.model?`${source.provider}/${source.model}`:undefined)]
    }
    if (event.type === 'tool/call') return [{ id, at, content: agentContent({ type: 'tool-call', callId: d.callId, name: d.name, input: input(d.arguments), status: 'in_progress' }) }]
    if (event.type === 'tool/result') {
        const m = object(d.message)
        return [{ id, at, content: agentContent({ type: 'tool-call-result', callId: m.toolCallId ?? object(m.source).callId, output: contentText(m.content), is_error: m.isError === true }) }]
    }
    if (event.type === 'turn/end' && d.reason?.kind === 'error') return [{id,at,content:agentContent({type:'error',message:text(d.reason.error?.message)||'Agent execution failed'})}]
    return []
}

export function projectOpencodeMessage(message: any, nativeSessionId?: string): NativeMessage[] {
    const id = text(message.id), at = Number(object(message.time).created) || 0
    if (message.type === 'user') return [{ id, at, content: userContent(text(message.text)),
        ...(id.startsWith('msg_hapi_') ? { inputLocalId: id.slice(9) }
            : nativeSessionId ? { inputLocalId: `opencode:${nativeSessionId}:queue:${id}` } : {}) }]
    if (message.type === 'assistant') {
      const parts = array(message.content).flatMap((part, index): NativeMessage[] => {
        const key = `${id}:${index}`, position = at
        if (part.type === 'tool') {
            const state = object(part.state)
            const completed = state.status === 'completed' || state.status === 'error'
            return [{ id: `${key}:call`, at: position, content: agentContent({ type: 'tool-call', callId: part.id, name: part.name, input: input(state.input ?? state.arguments), status: completed ? 'completed' : 'in_progress' }) },
                ...(completed ? [{ id: `${key}:result`, at: position, content: agentContent({ type: 'tool-call-result', callId: part.id, output: state.output ?? (state.content ? contentText(state.content) : state.error) ?? '', is_error: state.status === 'error' }) }] : [])]
        }
        return nativeBlocks(id, [part], at, object(message.time).completed == null).map(entry => ({ ...entry, id: key, at: position,
            updatedAt: Number(message.time?.completed ?? message.time?.streamed ?? at),
            content: agentContent({ ...object(object(entry.content).content).data, id: key }) }))
      })
      const tokens=object(message.tokens),cache=object(tokens.cache)
      if(message.error)parts.push({id:`${id}:error`,at,content:agentContent({type:'error',message:text(message.error.message)||JSON.stringify(message.error)})})
      if(message.time?.completed)parts.push(...usageMessage(`${id}:usage`,at,typeof tokens.input==='number'?{
        inputTokens:tokens.input,outputTokens:tokens.output+(tokens.reasoning??0),
        thoughtTokens:tokens.reasoning,cacheReadTokens:cache.read,cacheCreationTokens:cache.write,
      }:undefined,message.model?.providerID&&message.model?.id?`${message.model.providerID}/${message.model.id}`:undefined))
      return parts
    }
    if (message.type === 'compaction') return [
        ...(id.startsWith('msg_hapi_') ? [{id:`${id}:input`,at,content:userContent('/compact'),inputLocalId:id.slice(9)}] : []),
        ...(message.status === 'completed' && message.summary ? [{ id, at, content: agentContent({ type: 'message', message: message.summary, id }) }] : []),
    ]
    return []
}

/** Small native LLM block assembler; final block-end values replace partials. */
export class DshLiveBlocks {
    blocks = new Map<number, any>()
    apply(chunk: any): void {
        const index = Number(chunk.index)
        if (!Number.isInteger(index)) return
        if (chunk.type === 'block-start') this.blocks.set(index, { type: chunk.blockType, text: '' })
        else if (chunk.type === 'block-end') this.blocks.set(index, chunk.block)
        else if (chunk.type === 'text-delta' || chunk.type === 'reasoning-delta') {
            const prior = this.blocks.get(index) ?? { type: chunk.type === 'text-delta' ? 'text' : 'reasoning', text: '' }
            this.blocks.set(index, { ...prior, text: text(prior.text) + text(chunk.text) })
        }
    }
    seed(stream: unknown): void {
        for (const item of array(stream)) {
            if (item.type === 'chunk') this.apply(item.chunk)
            else if (item.type === 'text-chunks' || item.type === 'reasoning-chunks') for (const value of array(item.texts)) this.apply({ type: item.type === 'text-chunks' ? 'text-delta' : 'reasoning-delta', index: item.index, text: value })
        }
    }
    values(): unknown[] { return [...this.blocks.entries()].sort((a,b) => a[0]-b[0]).map(([,block]) => block) }
}
