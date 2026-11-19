import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { bootstrapSession, bootstrapExistingSession } from '@/agent/sessionFactory'
import type { ApiSessionClient } from '@/api/apiSession'
import type { ApiClient } from '@/api/api'
import { formatMessageWithAttachments } from '@/utils/attachmentFormatter'
import { getInvokedCwd } from '@/utils/invokedCwd'
import { RPC_METHODS, PERMISSION_REQUEST_NOT_FOUND_MESSAGE, type SharedNativeFlavor, type NativeMessage, type AttachmentMetadata } from '@hapi/protocol'
import { logger } from '@/ui/logger'
import { withRetry } from '@/utils/time'
import { isRetryableConnectionError } from '@/utils/errorUtils'
import { DshRuntime } from './dsh'
import { OpencodeRuntime } from './opencode'
import { object, array, text, type NativeRuntime, type NativeView } from './types'

export type NativeRunOptions = {
    startedBy?: 'runner'|'terminal'; workingDirectory?: string; existingSessionId?: string;
    reservedSessionId?: string; resumeSessionId?: string; model?: string;
    permissionMode?: string; effort?: string; modelReasoningEffort?: string | null;
}
export async function connectNativeRuntime(flavor: SharedNativeFlavor, runtime?: string, profile?: string, nativeSessionId?: string): Promise<NativeRuntime> {
    return flavor === 'dsh' ? DshRuntime.connect(runtime, profile, nativeSessionId) : OpencodeRuntime.connect(true)
}

/** One HAPI binding is an observer/controller, never the owner of an engine. */
export class NativeSessionBridge {
    private runtime?: NativeRuntime
    private closed = false
    private nativeId?: string
    private wake?: () => void
    private heartbeat?: ReturnType<typeof setInterval>
    private timer?: ReturnType<typeof setTimeout>
    private refreshing?: Promise<void>
    private dirty = false
    private emitted = new Map<string,string>()
    private view?: NativeView
    private pendingInputs = new Map<string, { content: string; delivery: 'queue'|'steer'; attachments?:AttachmentMetadata[] }>()
    private submitting = new Map<string,Promise<void>>()
    private queueIds = new Set<string>()
    private model: string|null = null
    private online = false
    private clearing?: { id:string; before:Set<string>; reserved:boolean }
    private clearTask?: Promise<void>
    private done!: () => void
    private readonly finished = new Promise<void>(resolve => {this.done=resolve})
    constructor(private session: ApiSessionClient, private flavor: SharedNativeFlavor, private options: NativeRunOptions, private api?: ApiClient) {
        this.nativeId = options.resumeSessionId ?? (flavor === 'dsh' ? session.getMetadata()?.dshSessionId : session.getMetadata()?.opencodeSessionId)
        session.onUserMessage((message, localId) => {
            const id=localId??randomUUID()
            this.pendingInputs.set(id,{content:message.content.text,attachments:message.content.attachments,delivery:'queue'})
            if(this.flavor==='opencode'&&message.content.text.trim()==='/clear'&&!message.content.attachments?.length&&!this.clearing){
                if(this.options.startedBy!=='runner'){
                    this.session.emitMessagesConsumed([id],{clearQueuedThinkingGrace:true});this.pendingInputs.delete(id)
                    this.session.sendSessionEvent({type:'error',message:'/clear is available for Runner-backed sessions.'});return
                }
                this.clearing={id,before:new Set([...this.pendingInputs.keys()].filter(key=>key!==id)),reserved:false}
                this.schedule();return
            }
            if(this.online&&!this.clearing)void this.submit(id).catch(error=>this.notice(id,error))
        })
        session.onRetryQueuedMessage(async id=>{await this.submit(id);return true})
        session.onCancelQueuedMessage(async id=>{
            if(this.clearing&&!this.clearing.reserved){
                if(id===this.clearing.id){this.pendingInputs.delete(id);this.clearing=undefined;for(const key of this.pendingInputs.keys())void this.submit(key).catch(error=>this.notice(key,error));return true}
                if(!this.clearing.before.has(id)){this.pendingInputs.delete(id);return true}
            }
            const entry=this.view?.queue.find(q=>(q.localId??`${this.source()}:queue:${q.id}`)===id)
            if(!entry)return this.view?.messages.some(m=>m.inputLocalId===id)?'consumed':'indeterminate'
            await this.runtime!.cancelQueued(entry.id);this.pendingInputs.delete(id);this.schedule();return true
        })
        session.on('hub-archived',()=>{if(!this.clearing?.reserved)void this.close()})
        this.registerControls()
    }
    private source():string{return `${this.flavor}:${this.nativeId}`}
    private notice(id:string,error:unknown):void{
        logger.debug('[native] input not confirmed',error)
        this.session.emitSteerIndeterminate([id])
        this.session.sendSessionEvent({type:'error',message:`${this.flavor} has not confirmed this input. Retry uses the same submission ID.`})
    }
    private async submit(id:string):Promise<void>{
        const existing=this.submitting.get(id);if(existing)return existing
        const work=(async()=>{
            const input=this.pendingInputs.get(id)
            if(!input)return
            if(this.clearing&&!this.clearing.before.has(id))return
            if(!this.runtime||!this.online)throw new Error('Native Host is reconnecting')
            await this.runtime.submit(id,input.content,input.delivery,input.attachments)
            // Acceptance is queued; only observed native history consumes it.
            this.schedule()
        })()
        this.submitting.set(id,work)
        try{await work}finally{this.submitting.delete(id)}
    }
    async run():Promise<void>{
        this.heartbeat=setInterval(()=>{this.alive();this.schedule()},5000)
        let attempt=0
        while(!this.closed){
            let runtime:NativeRuntime|undefined
            try{
                const metadata=this.session.getMetadata()
                runtime=await connectNativeRuntime(this.flavor,metadata?.nativeRuntime,metadata?.nativeProfile,this.nativeId)
                if(this.closed){await runtime.close();break}
                let disconnected!:()=>void
                const lost=new Promise<void>(resolve=>{disconnected=resolve;this.wake=resolve})
                runtime.changed=()=>this.schedule()
                runtime.disconnected=()=>{this.online=false;this.state('disconnected');disconnected()}
                const fresh = !this.nativeId
                if(!this.nativeId){
                    const created=await runtime.create(this.options.workingDirectory??getInvokedCwd(),this.options.model)
                    this.nativeId=created.id
                }
                this.runtime=runtime
                this.session.updateMetadata(m=>({...m,nativeShared:true,nativeRuntime:runtime!.runtime,nativeProfile:runtime!.profile,
                    ...(this.flavor==='dsh'?{dshSessionId:this.nativeId}:{opencodeSessionId:this.nativeId}),
                    capabilities:{...m.capabilities,concurrentClients:true}}))
                await this.session.flush({timeoutMs:10_000})
                await runtime.bind(this.nativeId)
                if(fresh&&runtime.configure){
                    await runtime.configure({
                        ...(this.options.permissionMode&&this.options.permissionMode!=='default'?{permissionMode:this.options.permissionMode}:{}),
                        ...(this.options.modelReasoningEffort&&this.options.modelReasoningEffort!=='default'?{modelReasoningEffort:this.options.modelReasoningEffort}:{}),
                    })
                }
                this.online=true;this.emitted.clear();await this.refresh(true)
                this.state('connected');this.session.emitSessionReady();attempt=0
                for(const id of this.pendingInputs.keys())void this.submit(id).catch(error=>this.notice(id,error))
                await Promise.race([lost,this.finished])
            }catch(error){
                logger.debug(`[native:${this.flavor}] connect`,error instanceof Error ? error.stack ?? error.message : String(error))
                this.online=false;this.state('disconnected')
                if(!this.nativeId)throw error
            }finally{
                this.online=false;await runtime?.close();await this.refreshing?.catch(()=>{});if(this.runtime===runtime)this.runtime=undefined
            }
            if(!this.closed)await Promise.race([delay(Math.min(10_000,500*2**Math.min(attempt++,5))),this.finished])
        }
    }
    private state(status:'connected'|'disconnected'):void{
        this.session.updateAgentState(s=>({...s,nativeRuntimeStatus:status,controlledByUser:false,startingMode:'remote',
            ...(status==='disconnected'?{requests:{},nativeCompacting:false}:{})}))
    }
    private alive():void{this.session.keepAlive(this.online&&this.view?.running===true,'remote',{model:this.model,...(this.view?.effort!==undefined?{modelReasoningEffort:this.view.effort}:{})})}
    private schedule():void{
        if(this.closed)return
        this.dirty=true
        if(this.timer)return
        this.timer=setTimeout(()=>{this.timer=undefined;void this.refresh().catch(error=>logger.debug('[native] refresh',error))},150)
    }
    private async refresh(full=false):Promise<void>{
        if(this.refreshing){this.dirty=true;return this.refreshing}
        if(!this.runtime||!this.online)return
        const runtime=this.runtime
        this.dirty=false
        this.refreshing=(async()=>{
            const view=await runtime.view(full)
            if(this.closed||runtime!==this.runtime||!this.online)return
            this.view=view
            const messages=new Map(view.messages.map(m=>[m.id,m]))
            const changed=[...messages.values()].filter(m=>this.emitted.get(m.id)!==JSON.stringify(m))
            const removed=[...this.emitted.keys()].filter(id=>!messages.has(id))
            for(let i=0;i<Math.max(changed.length,removed.length,1);i+=400){
                const entries=changed.slice(i,i+400),withdrawn=removed.slice(i,i+400)
                const reconcile=full&&i+400>=Math.max(changed.length,removed.length)?[...messages.keys()]:undefined
                if(!entries.length&&!withdrawn.length&&!reconcile)continue
                await this.session.syncNativeMessageBatch({source:this.source(),messages:entries,removed:withdrawn,...(reconcile?{reconcile}:{})})
                for(const message of entries){this.emitted.set(message.id,JSON.stringify(message));if(message.inputLocalId)this.pendingInputs.delete(message.inputLocalId)}
                for(const id of withdrawn)this.emitted.delete(id)
            }
            const queued=new Set<string>()
            for(const item of view.queue){const id=item.localId??`${this.source()}:queue:${item.id}`;queued.add(id);const own=this.pendingInputs.get(id);this.session.syncNativeQueuedMessage(id,own?.attachments?.length?own.content:item.text)}
            for(const id of this.queueIds)if(!queued.has(id)&&!view.messages.some(m=>m.inputLocalId===id))this.session.syncNativeQueuedMessage(id,null)
            this.queueIds=queued
            const previous=this.session.getAgentState()
            const requests=Object.fromEntries(view.requests.map(r=>[r.id,{tool:r.tool,toolCallId:r.toolCallId,arguments:r.arguments,createdAt:previous?.requests?.[r.id]?.createdAt??Date.now()}]))
            if(JSON.stringify(previous?.requests??{})!==JSON.stringify(requests)||previous?.nativeCompacting!==Boolean(view.compacting)){
                this.session.updateAgentState(s=>({...s,requests,nativeCompacting:Boolean(view.compacting)}))
            }
            if(view.title&&view.title!==this.session.getMetadata()?.name)this.session.updateMetadata(m=>({...m,name:view.title}))
            this.model=view.model??this.model;this.alive()
            if(this.clearing&&!this.clearTask&&!view.running&&!view.queue.length&&!this.submitting.size
                &&![...this.clearing.before].some(id=>this.pendingInputs.has(id))){
                this.clearTask=this.finishClear().catch(error=>{
                    logger.debug('[native] clear handoff failed',error instanceof Error?error.message:String(error))
                    this.session.sendSessionEvent({type:'error',message:'Conversation clear was not confirmed. Reopen the session to recover the handoff.'})
                    return this.close()
                })
            }
        })()
        try{await this.refreshing}finally{this.refreshing=undefined;if(this.dirty)this.schedule()}
    }
    private async finishClear():Promise<void>{
        const clear=this.clearing
        if(!clear||!this.api)return
        const retry={minDelay:500,maxDelay:30_000,shouldRetry:isRetryableConnectionError}
        clear.reserved=true
        const replacement=await withRetry(()=>this.api!.reserveOpenCodeClearSession(this.session.sessionId),retry)
        this.online=false
        await this.runtime?.close()
        await withRetry(()=>this.api!.confirmOpenCodeClearCleanup(this.session.sessionId,replacement),retry)
        this.session.emitMessagesConsumed([clear.id],{clearQueuedThinkingGrace:true})
        this.session.updateMetadata(m=>({...m,lifecycleState:'archived',lifecycleStateSince:Date.now(),archivedBy:'cli',archiveReason:'Cleared by /clear'}))
        this.session.sendSessionDeath('cleared',{preserveUploads:true})
        await withRetry(async()=>{if(!await this.session.flush({timeoutMs:5000}))throw new Error('Clear acknowledgement timeout')},retry)
        await withRetry(()=>this.api!.clearOpenCodeSession(this.session.sessionId),retry)
        await this.close('cleared')
    }
    private registerControls():void{
        const register=(method:string,handler:(data:any)=>Promise<unknown>)=>this.session.rpcHandlerManager.registerHandler(method,handler)
        register(RPC_METHODS.Permission,async raw=>{
            const request=this.view?.requests.find(r=>r.id===raw.id)
            if(!request||!this.runtime||!this.online)throw new Error(PERMISSION_REQUEST_NOT_FOUND_MESSAGE)
            await this.runtime.reply(raw.id,raw);this.schedule();return {success:true}
        })
        register(RPC_METHODS.Abort,async()=>{await this.runtime!.cancel();this.schedule()})
        register(RPC_METHODS.KillSession,async()=>{void this.close();return {success:true}})
        register(RPC_METHODS.SteerQueuedMessage,async raw=>{
            const entry=this.view?.queue.find(q=>(q.localId??`${this.source()}:queue:${q.id}`)===raw.localId)
            if(entry&&this.runtime&&this.online){await this.runtime.steerQueued(entry.id);this.schedule();return {steered:true}}
            const input=this.pendingInputs.get(raw.localId)
            if(!input||!this.runtime||!this.online)return {steered:false,error:'Input is not queued'}
            input.delivery='steer';await this.submit(raw.localId);return {steered:true}
        })
        register(RPC_METHODS.SetSessionConfig,async raw=>{
            if(!this.runtime?.configure)throw new Error('Settings are managed by the native Host')
            const config = object(raw)
            await this.runtime.configure(config)
            this.schedule()
            return { applied: config }
        })
        register(RPC_METHODS.Switch,async()=>{throw new Error('control_mode_not_applicable')})
        register(RPC_METHODS.HandoffLocal,async()=>{throw new Error('control_mode_not_applicable')})
        if(this.flavor==='opencode'){
            register(RPC_METHODS.ListSlashCommands,async()=>({commands:[{name:'compact',description:'Compact the native conversation'}]}))
            const catalog=async()=>array(object(await this.runtime?.models?.()).data)
            register(RPC_METHODS.ListOpencodeModels,async()=>({success:true,currentModelId:this.model,
                availableModels:(await catalog()).map(m=>({modelId:`${m.providerID}/${m.id}`,name:m.name??m.id}))}))
            register(RPC_METHODS.ListOpencodeReasoningEffortOptions,async()=>{
                const row=(await catalog()).find(m=>`${m.providerID}/${m.id}`===this.model)
                return {success:true,currentModelId:this.model,targetModelId:this.model,currentValue:this.view?.effort??'default',
                    options:array(row?.variants).map(v=>typeof v==='string'?{value:v}:{value:v.id,name:v.name??v.id})}
            })
        }
    }
    async close(reason:'terminated'|'cleared'='terminated'):Promise<void>{
        if(this.closed)return
        this.closed=true;this.done();this.wake?.()
        if(this.heartbeat)clearInterval(this.heartbeat)
        if(this.timer)clearTimeout(this.timer)
        await this.runtime?.close()
        this.session.updateAgentState(s=>({...s,requests:{},nativeRuntimeStatus:'disconnected',nativeCompacting:false}))
        if(reason!=='cleared')this.session.sendSessionDeath(reason,{preserveUploads:true})
        await this.session.flush({timeoutMs:5000});await this.session.close()
    }
}

export async function runSharedNativeAgent(flavor:SharedNativeFlavor,options:NativeRunOptions):Promise<void>{
    const workingDirectory=options.workingDirectory??getInvokedCwd()
    const bootstrap=options.existingSessionId
        ?await bootstrapExistingSession({sessionId:options.existingSessionId,flavor,startedBy:options.startedBy,workingDirectory})
        :await bootstrapSession({flavor,startedBy:options.startedBy,workingDirectory,reservedSessionId:options.reservedSessionId,
            metadataOverrides:{nativeShared:true,capabilities:{concurrentClients:true}},
            agentState:{controlledByUser:false,startingMode:'remote',nativeRuntimeStatus:'disconnected'}})
    const bridge=new NativeSessionBridge(bootstrap.session,flavor,{...options,workingDirectory},bootstrap.api)
    const stop=()=>{void bridge.close()}
    process.on('SIGTERM',stop);process.on('SIGINT',stop)
    try{await bridge.run()}finally{process.off('SIGTERM',stop);process.off('SIGINT',stop);await bridge.close()}
}
