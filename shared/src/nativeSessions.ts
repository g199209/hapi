import { z } from 'zod'

export const SharedNativeFlavorSchema = z.enum(['opencode', 'dsh'])
export type SharedNativeFlavor = z.infer<typeof SharedNativeFlavorSchema>

export const NativeSessionSummarySchema = z.object({
    id: z.string().min(1), title: z.string(), cwd: z.string().min(1),
    modifiedAt: z.number(), basePath: z.string().optional(),
    runtime: z.string().optional(), profile: z.string().optional(),
    active: z.boolean().optional(), hapiSessionId: z.string().optional(),
})
export type NativeSessionSummary = z.infer<typeof NativeSessionSummarySchema>
export const ListNativeSessionsRequestSchema = z.object({
    flavor: SharedNativeFlavorSchema,
    sessionId: z.string().optional(), runtime: z.string().optional(),
})
export type ListNativeSessionsRequest = z.infer<typeof ListNativeSessionsRequestSchema>
export const ListNativeSessionsResponseSchema = z.object({
    success: z.boolean(), sessions: z.array(NativeSessionSummarySchema).default([]), error: z.string().optional(),
})
export type ListNativeSessionsResponse = z.infer<typeof ListNativeSessionsResponseSchema>

/** Mutable native snapshots keep a stable database/UI identity. Only this
 * source's IDs can be withdrawn; ordinary HAPI messages are never replaced. */
export const NativeMessageSchema = z.object({
    id: z.string().min(1), at: z.number().int().nonnegative(),
    createdAt: z.number().int().nonnegative().optional(),
    updatedAt: z.number().finite().nonnegative().optional(),
    content: z.unknown(), inputLocalId: z.string().min(1).optional(),
})
export type NativeMessage = z.infer<typeof NativeMessageSchema>
export const NativeMessageBatchSchema = z.object({
    sid: z.string(), source: z.string().min(1),
    messages: z.array(NativeMessageSchema).max(400),
    removed: z.array(z.string()).max(2000).default([]),
    /** Final baseline page withdraws source rows absent from the canonical view. */
    reconcile: z.array(z.string()).max(100000).optional(),
})
export type NativeMessageBatch = z.infer<typeof NativeMessageBatchSchema>
