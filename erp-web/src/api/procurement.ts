import { request } from './http'
import type { PageResult } from '../types/api'
import type { ProcurementDocument,ProcurementKind } from '../types/procurement'
export function procurementList(kind:ProcurementKind,params:Record<string,unknown>={}):Promise<PageResult<ProcurementDocument>>{
 return request({method:'GET',url:'/'+kind,params},true)
}
export function procurementDocument(kind:ProcurementKind,id:string,signal?:AbortSignal):Promise<ProcurementDocument>{
 return request({method:'GET',url:'/'+kind+'/'+id,...(signal?{signal}:{})},true)
}
export function saveProcurement(kind:ProcurementKind,data:Record<string,unknown>,id?:string,key?:string):Promise<ProcurementDocument>{
 return request({method:id?'PUT':'POST',url:'/'+kind+(id?'/'+id:''),data,headers:key?{'Idempotency-Key':key}:undefined},true)
}
export function procurementCommand(kind:ProcurementKind,id:string,action:string,data:Record<string,unknown>,key:string,signal?:AbortSignal):Promise<ProcurementDocument>{
 return request({method:action==='delete'?'DELETE':'POST',url:'/'+kind+'/'+id+(action==='delete'?'':'/'+action.replaceAll('_','-')),data,params:action==='delete'?{version:data.version}:undefined,headers:{'Idempotency-Key':key},...(signal?{signal}:{})},true)
}
