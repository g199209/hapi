import type { NativeSessionSummary, SharedNativeFlavor } from '@hapi/protocol'
import { DshRuntime } from './dsh'
import { OpencodeRuntime } from './opencode'
import { dshDescriptors } from './dshConnection'

export async function discoverNativeSessions(flavor: SharedNativeFlavor): Promise<NativeSessionSummary[]> {
    if(flavor==='opencode'){
        const runtime=await OpencodeRuntime.connect(true)
        try{return await runtime.list()}finally{await runtime.close()}
    }
    const descriptors=await dshDescriptors()
    const sessions=new Map<string,NativeSessionSummary>()
    const errors:string[]=[]
    let connected=0
    for(const descriptor of descriptors){
        let runtime:DshRuntime|undefined
        try{
            runtime=await DshRuntime.connectDescriptor(descriptor)
            connected++
            for(const row of await runtime.list()){
                const existing=sessions.get(row.id)
                if(!existing||(!existing.active&&row.active))sessions.set(row.id,row)
            }
        }catch(error){errors.push(error instanceof Error?error.message:'Host unavailable')}
        finally{await runtime?.close()}
    }
    if(!connected)throw new Error(errors[0]??'No running DSH Host with discovery plugin')
    return [...sessions.values()]
}
