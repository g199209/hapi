import { Hono } from 'hono'
import { z } from 'zod'
import { SharedNativeFlavorSchema } from '@hapi/protocol'
import type { Metadata } from '@hapi/protocol'
import type { Store } from '../../store'
import type { SyncEngine } from '../../sync/syncEngine'
import type { WebAppEnv } from '../middleware/auth'

/** Discovery is machine-scoped; the Hub never reads an agent's local files. */
export function createNativeSessionRoutes(options: { store: Store; getSyncEngine: () => SyncEngine | null }): Hono<WebAppEnv> {
    const app = new Hono<WebAppEnv>()
    app.get('/native/sessions', async c => {
        const flavor = SharedNativeFlavorSchema.safeParse(c.req.query('flavor'))
        const engine = options.getSyncEngine()
        const machineId = c.req.query('machineId')
        const machine = engine?.getOnlineMachinesByNamespace(c.get('namespace')).find(m => m.id === machineId)
        if (!flavor.success || !machine || !engine) return c.json({success:false,sessions:[],error:'Select an online machine and native agent'},400)
        if (!machine.metadata?.capabilities?.includes('listNativeSessions')) return c.json({success:false,sessions:[],error:'Runner upgrade required for native session import'},409)
        try {
            const result = await engine.listNativeSessionsForMachine(machine.id,{flavor:flavor.data})
            return c.json({...result,machineId:machine.id})
        } catch (error) { return c.json({success:false,sessions:[],error:error instanceof Error?error.message:'Runner native discovery requires an upgrade'},502) }
    })
    app.post('/native/import', async c => {
        const request = z.object({flavor:SharedNativeFlavorSchema,machineId:z.string(),sessionIds:z.array(z.string().min(1)).min(1).max(20)}).safeParse(await c.req.json().catch(()=>null))
        const engine=options.getSyncEngine(),namespace=c.get('namespace')
        if(!request.success||!engine)return c.json({success:false,error:'Invalid native import'},400)
        const {flavor,machineId,sessionIds}=request.data
        const machine=engine.getOnlineMachinesByNamespace(namespace).find(m=>m.id===machineId)
        if(!machine)return c.json({success:false,error:'Machine is offline'},409)
        if(!machine.metadata?.capabilities?.includes('listNativeSessions'))return c.json({success:false,error:'Runner upgrade required for native session import'},409)
        try{
            const catalog=await engine.listNativeSessionsForMachine(machineId,{flavor})
            if(!catalog.success)throw new Error(catalog.error??'Native discovery failed')
            if(sessionIds.some(id=>!catalog.sessions.some(s=>s.id===id)))throw new Error('Native session is unavailable or outside workspace roots')
            const ids:string[]=[]
            for(const nativeId of [...new Set(sessionIds)]){
                const row=catalog.sessions.find(s=>s.id===nativeId)
                if(!row)throw new Error('Native session is unavailable or outside workspace roots')
                const field=flavor==='dsh'?'dshSessionId':'opencodeSessionId'
                const existing=options.store.sessions.getSessionsByNamespace(namespace).find(s=>{
                    const m=s.metadata as Metadata|null
                    return m?.flavor===flavor&&m.machineId===machineId&&m[field]===nativeId
                })
                if(existing){ids.push(existing.id);continue}
                const metadata:Metadata={path:row.cwd,host:machine.metadata?.host??machineId,os:machine.metadata?.platform??'unknown',
                    machineId,flavor,name:row.title,[field]:nativeId,nativeShared:true,nativeRuntime:row.runtime,nativeProfile:row.profile,
                    lifecycleState:'running',capabilities:{concurrentClients:true}}
                const session=engine.getOrCreateSession(`native-import:${machineId}:${flavor}:${nativeId}`,metadata,null,namespace)
                ids.push(session.id)
            }
            return c.json({success:true,hapiSessionIds:ids})
        }catch(error){return c.json({success:false,error:error instanceof Error?error.message:'Native import failed'},502)}
    })
    return app
}
