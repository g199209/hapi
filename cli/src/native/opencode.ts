import { readFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { opencodeExecutable } from '@/opencode/utils/opencodeExecutable'
import { projectOpencodeMessage, canonicalMessages } from './projection'
import { array, object, text, type NativeRuntime, type NativeView, type NativeReply } from './types'
import type { NativeSessionSummary, AttachmentMetadata } from '@hapi/protocol'

export class OpencodeRuntime implements NativeRuntime {
    readonly flavor = 'opencode' as const
    changed = () => {}
    disconnected = (_error: Error) => {}
    private id = ''
    private cwd = ''
    private messages = new Map<string, any>()
    private full = true
    private forms: any[] = []
    private permissions: any[] = []
    private abort = new AbortController()
    private constructor(readonly runtime: string, private base: string, private authorization: string) {}
    static async connect(start = false): Promise<OpencodeRuntime> {
        const path = process.env.HAPI_OPENCODE_SERVICE_FILE ?? join(process.env.XDG_STATE_HOME ?? join(homedir(), '.local', 'state'), 'opencode', 'service.json')
        for (let attempt = 0; ; attempt++) {
            let service: any
            try { service = JSON.parse(await readFile(path, 'utf8')) }
            catch (error) {
                if (!start || attempt > 0 || (error as NodeJS.ErrnoException).code !== 'ENOENT') throw new Error('OpenCode shared service descriptor is unavailable')
                await this.startService()
                continue
            }
            const url = new URL(service.url)
            if (!['http:', 'https:'].includes(url.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
                || !service.password || !/^2\./.test(service.version)) throw new Error('OpenCode 2 shared service descriptor is invalid')
            const runtime = new OpencodeRuntime(service.id, url.origin, `Basic ${Buffer.from(`opencode:${service.password}`).toString('base64')}`)
            const timer = setTimeout(() => runtime.abort.abort(), 15_000)
            try {
                const response = await fetch(`${url.origin}/api/event`, {
                    headers: { Authorization: runtime.authorization }, signal: runtime.abort.signal,
                })
                if (!response.ok || !response.body) {
                    runtime.abort.abort()
                    if (response.status === 401 && attempt === 0) continue // Re-read a rotated registration, without restarting its owner.
                    throw new Error(`OpenCode event connection HTTP ${response.status}`)
                }
                void runtime.events(response.body).catch(error => { if (!runtime.abort.signal.aborted) runtime.disconnected(error) })
                return runtime
            } catch (error) {
                runtime.abort.abort()
                const code = object(error).code ?? object(object(error).cause).code
                if (!start || attempt > 0 || code !== 'ECONNREFUSED') throw error
                // A healthy App-owned service is always reused. Only an absent
                // listener permits the native CLI to ensure the shared service.
                await this.startService()
            } finally { clearTimeout(timer) }
        }
    }
    private static async startService(): Promise<void> {
        await promisify(execFile)(opencodeExecutable(), ['service', 'start'], { windowsHide: true, timeout: 30_000 })
    }
    async call(method: string, path: string, data?: unknown): Promise<any> {
        const response = await fetch(`${this.base}/api/${path}`, { method, headers: { Authorization: this.authorization, 'content-type':'application/json' },
            ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.any([this.abort.signal, AbortSignal.timeout(30_000)]) })
        if (!response.ok) throw new Error(`OpenCode ${method} ${path} HTTP ${response.status}`)
        return response.status === 204 ? undefined : response.json()
    }
    private async events(body: ReadableStream<Uint8Array>): Promise<void> {
        const reader = body.getReader(), decoder = new TextDecoder()
        let buffer = ''
        try {
            while (true) {
                const {done,value}=await reader.read();if(done)throw new Error('OpenCode event stream ended')
                buffer=(buffer+decoder.decode(value,{stream:true})).replace(/\r\n/g,'\n')
                let index: number
                while((index=buffer.indexOf('\n\n'))>=0){
                    const frame=buffer.slice(0,index);buffer=buffer.slice(index+2)
                    const data=frame.split('\n').filter(l=>l.startsWith('data:')).map(l=>l.slice(5).trimStart()).join('\n')
                    if(!data)continue
                    let event:any=JSON.parse(data);if(typeof event==='string')event=JSON.parse(event)
                    if(this.id && JSON.stringify(event).includes(this.id)){
                        if(/revert|delete|compact/.test(text(event.type)))this.full=true
                        this.changed()
                    }
                }
            }
        }finally{reader.releaseLock()}
    }
    async list(): Promise<NativeSessionSummary[]> {
        const rows:any[]=[],seen=new Set<string>();let cursor:string|undefined
        do {
            const page=await this.call('GET',`session?limit=100${cursor?`&cursor=${encodeURIComponent(cursor)}`:''}`)
            rows.push(...array(page.data));cursor=page.cursor?.next
            if(cursor&&(typeof cursor!=='string'||seen.has(cursor)))throw new Error('OpenCode catalog pagination repeated a cursor')
            if(cursor)seen.add(cursor)
        }while(cursor)
        return rows.filter(s=>s.location?.directory&&!s.parentID&&!s.time?.archived).map(s=>({id:s.id,cwd:s.location.directory,title:text(s.title)||s.id,modifiedAt:s.time?.updated??s.time?.created??0,runtime:this.runtime}))
    }
    async create(cwd: string, model?: string): Promise<NativeSessionSummary> {
        const response=await this.call('POST','session',{location:{directory:cwd},...(model?{model:this.modelRef(model)}:{})})
        return {id:response.data.id,cwd,title:text(response.data.title)||'OpenCode',modifiedAt:response.data.time.created,runtime:this.runtime}
    }
    async bind(id: string): Promise<void>{this.id=id;const info=await this.call('GET',`session/${encodeURIComponent(id)}`);this.cwd=info.data.location.directory}
    async view(full = false): Promise<NativeView> {
        const root=`session/${encodeURIComponent(this.id)}`
        const [info,active,inbox,forms,permissions]=await Promise.all([
            this.call('GET',root),this.call('GET','session/active'),this.call('GET',`${root}/inbox`),
            this.call('GET',`${root}/form`),this.call('GET',`${root}/permission`),
        ])
        const rebuild=full||this.full;this.full=false
        const collected=new Map<string,any>(),seen=new Set<string>();let cursor:string|undefined
        do {
            const page=await this.call('GET',`${root}/message?limit=100${cursor?`&cursor=${encodeURIComponent(cursor)}`:'&order=desc'}`)
            const rows=array(page.data)
            for(const row of rows)collected.set(row.id,row)
            if(!rebuild&&rows.some(row=>this.messages.has(row.id)))break
            cursor=page.cursor?.next
            if(cursor&&seen.has(cursor))throw new Error('OpenCode history pagination repeated a cursor')
            if(cursor)seen.add(cursor)
        }while(cursor)
        if(rebuild)this.messages.clear()
        for(const id of collected.keys())this.messages.delete(id)
        for(const [id,row]of [...collected].reverse())this.messages.set(id,row)
        this.forms=array(forms.data);this.permissions=array(permissions.data)
        const messages=[...this.messages.values()]
        const requests=[...this.forms.map(f=>({id:`form:${f.id}`,tool:'request_user_input',arguments:{questions:array(f.fields).filter(q=>!q.hidden).map(q=>({id:q.key,question:q.title??f.title,options:array(q.options).map(o=>({label:o.label,description:o.description})),multiSelect:q.type==='multiselect'}))}})),
            ...this.permissions.map(p=>({id:`permission:${p.id}`,tool:p.action??'Tool',toolCallId:p.source?.callID,arguments:{...p,command:p.resources?.join(' ')}}))]
        const current=object(info.data??info)
        const model=object(current.model)
        return {messages:canonicalMessages(messages.flatMap(message=>projectOpencodeMessage(message,this.id))),running:Boolean(active.data?.[this.id]),
            compacting:messages.some(m=>m.type==='compaction'&&m.status==='running'),requests,title:text(current.title)||undefined,
            ...(model.id?{model:`${model.providerID}/${model.id}`,effort:model.variant??null}:{}),
            queue:array(inbox.data).filter(q=>q.type==='user'||q.type==='compaction').map(q=>({id:q.id,...(q.id.startsWith('msg_hapi_')?{localId:q.id.slice(9)}:{}),text:q.type==='compaction'?'/compact':text(q.payload?.text)}))}
    }
    async submit(localId:string,content:string,delivery:'queue'|'steer',attachments?:AttachmentMetadata[]):Promise<void>{
        if(content.trim()==='/compact'&&!attachments?.length){await this.call('POST',`session/${this.id}/compact`,{id:`msg_hapi_${localId}`,delivery});return}
        await this.call('POST',`session/${this.id}/prompt`,{id:`msg_hapi_${localId}`,text:content,delivery,
            ...(attachments?.length?{files:attachments.map(file=>({uri:pathToFileURL(file.path).href,name:file.filename}))}:{})})
    }
    async cancel():Promise<void>{await this.call('POST',`session/${this.id}/interrupt`,{})}
    async cancelQueued(id:string):Promise<void>{await this.call('DELETE',`session/${this.id}/inbox/${encodeURIComponent(id)}`)}
    async steerQueued(id:string):Promise<void>{await this.call('PATCH',`session/${this.id}/inbox/${encodeURIComponent(id)}`,{delivery:'steer'})}
    async reply(id:string,reply:NativeReply):Promise<void>{
        if(id.startsWith('permission:')){await this.call('POST',`session/${this.id}/permission/${encodeURIComponent(id.slice(11))}/reply`,{decision:reply.approved?'once':'reject',...(reply.reason?{message:reply.reason}:{})});return}
        const form=this.forms.find(f=>`form:${f.id}`===id)
        if(!form)throw new Error('Permission request not found or already resolved')
        if(!reply.approved){await this.call('DELETE',`session/${this.id}/form/${form.id}`);return}
        const answer=Object.fromEntries(array(form.fields).filter(q=>!q.hidden).map(q=>{
            const raw=reply.answers?.[q.key];const values=Array.isArray(raw)?raw:array(object(raw).answers)
            const mapped=values.map(v=>array(q.options).find(o=>o.label===v)?.value??v)
            return [q.key,q.type==='multiselect'?mapped:q.type==='boolean'?mapped[0]==='true':q.type==='integer'||q.type==='number'?Number(mapped[0]):mapped[0]??'']
        }))
        await this.call('POST',`session/${this.id}/form/${form.id}/reply`,{answer})
    }
    private modelRef(model:string){const index=model.indexOf('/');if(index<1)throw new Error('Select a provider/model');return {providerID:model.slice(0,index),id:model.slice(index+1)}}
    async configure(config:Record<string,unknown>):Promise<void>{
        for (const key of Object.keys(config)) {
            if (!['model', 'modelReasoningEffort', 'permissionMode'].includes(key)) throw new Error(`OpenCode does not support setting ${key}`)
        }
        if ('model' in config && typeof config.model !== 'string') throw new Error('Select an explicit OpenCode provider/model')
        if(typeof config.model==='string'||'modelReasoningEffort' in config){
            const current=typeof config.model==='string'?this.modelRef(config.model):(await this.call('GET',`session/${this.id}`)).data.model
            const variant='modelReasoningEffort' in config ? config.modelReasoningEffort : current.variant
            const model={...current,...(variant&&variant!=='default'?{variant}:{})}
            if(variant == null || variant === 'default')delete model.variant
            await this.call('POST',`session/${this.id}/model`,{model})
        }
        if(typeof config.permissionMode==='string'){
            if(config.permissionMode==='plan')await this.call('POST',`session/${this.id}/agent`,{agent:'plan'})
            else if(config.permissionMode==='yolo'||config.permissionMode==='default'){
                await this.call('POST',`session/${this.id}/agent`,{agent:'build'})
                await this.call('PATCH',`session/${this.id}`,{permissions:config.permissionMode==='yolo'?[{action:'*',resource:'*',effect:'allow'}]:[]})
            }
        }
    }
    async models():Promise<unknown>{return this.call('GET',`model${this.cwd?'?'+new URLSearchParams({'location[directory]':this.cwd}):''}`)}
    async close():Promise<void>{this.abort.abort();this.authorization=''}
}
