import { describe, expect, it } from 'bun:test'
import { Hono } from 'hono'
import { Store } from '../../store'
import type { SyncEngine } from '../../sync/syncEngine'
import type { WebAppEnv } from '../middleware/auth'
import { createNativeSessionRoutes } from './nativeSessions'

function fixture(capable=true) {
    const store=new Store(':memory:')
    const catalog=[{id:'native',cwd:'/project/worktree',basePath:'/project',title:'Native title',modifiedAt:123,runtime:'instance',profile:'desktop'}]
    const engine={
        getOnlineMachinesByNamespace:(namespace:string)=>namespace==='default'?[{id:'machine',metadata:{host:'host',platform:'linux',capabilities:capable?['listNativeSessions']:[]}}]:[],
        listNativeSessionsForMachine:async()=>({success:true,sessions:catalog}),
        getOrCreateSession:(tag:string,metadata:unknown,state:unknown,namespace:string)=>store.sessions.getOrCreateSession(tag,metadata,state,namespace),
    } as unknown as SyncEngine
    const app=new Hono<WebAppEnv>()
    app.use('*',async(c,next)=>{c.set('namespace',c.req.header('x-test-namespace')??'default');await next()})
    app.route('/api',createNativeSessionRoutes({store,getSyncEngine:()=>engine}))
    const post=(data:unknown,namespace='default')=>app.request('/api/native/import',{method:'POST',headers:{'content-type':'application/json','x-test-namespace':namespace},body:JSON.stringify(data)})
    return {app,store,catalog,post}
}

describe('native import routes',()=>{
    it('discovers only the selected online machine in the caller namespace',async()=>{
        const f=fixture()
        const response=await f.app.request('/api/native/sessions?machineId=machine&flavor=dsh')
        expect(await response.json()).toMatchObject({success:true,machineId:'machine',sessions:[{id:'native',basePath:'/project'}]})
        const denied=await f.app.request('/api/native/sessions?machineId=machine&flavor=dsh',{headers:{'x-test-namespace':'other'}})
        expect(denied.status).toBe(400)
        expect((await denied.json() as any).sessions).toEqual([])
    })
    it('creates one binding under concurrent imports and preserves native identity/settings',async()=>{
        const f=fixture(),request={machineId:'machine',flavor:'dsh',sessionIds:['native']}
        const [a,b]=await Promise.all([f.post(request),f.post(request)])
        const first=await a.json() as any,second=await b.json() as any
        expect(first.success).toBe(true)
        expect(first.hapiSessionIds).toEqual(second.hapiSessionIds)
        const sessions=f.store.sessions.getSessionsByNamespace('default')
        expect(sessions).toHaveLength(1)
        expect(sessions[0].metadata).toMatchObject({flavor:'dsh',dshSessionId:'native',nativeShared:true,nativeRuntime:'instance',nativeProfile:'desktop',path:'/project/worktree'})
        expect(sessions[0].model).toBeNull()
        expect((sessions[0].metadata as any).permissionMode).toBeUndefined()
    })
    it('validates every selection before creating records and rejects stale/foreign IDs',async()=>{
        const f=fixture()
        const result=await f.post({machineId:'machine',flavor:'opencode',sessionIds:['native','unavailable']})
        expect(result.status).toBe(502)
        expect(f.store.sessions.getSessionsByNamespace('default')).toHaveLength(0)
    })
    it('reports unsupported runners immediately and validates the agent',async()=>{
        const f=fixture(false)
        expect((await f.app.request('/api/native/sessions?machineId=machine&flavor=dsh')).status).toBe(409)
        expect((await f.post({machineId:'machine',flavor:'dsh',sessionIds:['native']})).status).toBe(409)
        expect((await f.post({machineId:'machine',flavor:'codex',sessionIds:['native']})).status).toBe(400)
    })
})
