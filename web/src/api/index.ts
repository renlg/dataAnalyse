import axios from 'axios'

export const http=axios.create({baseURL:'/api',timeout:30000})
http.interceptors.response.use(response=>{const result=response.data;if(result.code!==0)return Promise.reject(new Error(result.message||'请求失败'));return result.data},error=>Promise.reject(new Error(error.response?.data?.message||error.message||'网络请求失败')))

export interface DataSourceItem {id:number;name:string;type:'sqlite'|'h2'|'mysql';host?:string;port?:number;databaseName?:string;username?:string;password?:string;jdbcUrl?:string;online?:boolean;createdAt?:string}
export interface WorkflowItem {id:number;name:string;description?:string;status:'draft'|'active'|'disabled';nodeCount:number;createdAt?:string;updatedAt?:string;nodes?:WorkflowNodeDto[]}
export interface WorkflowNodeDto {id?:number;nodeKey:string;nodeType:string;name:string;positionX:number;positionY:number;config:Record<string,unknown>}
export interface RunItem {id:number;workflowId:number;status:string;startedAt:string;finishedAt?:string;logs?:string}

export const datasourceApi={list:()=>http.get<never,DataSourceItem[]>('/datasources'),create:(data:Partial<DataSourceItem>)=>http.post<never,DataSourceItem>('/datasources',data),update:(id:number,data:Partial<DataSourceItem>)=>http.put<never,DataSourceItem>(`/datasources/${id}`,data),remove:(id:number)=>http.delete(`/datasources/${id}`),test:(id:number)=>http.post(`/datasources/${id}/test`),query:(id:number,sql:string)=>http.post<never,{columns:string[];rows:Record<string,unknown>[]}>(`/datasources/${id}/query`,{sql})}
export const workflowApi={list:()=>http.get<never,WorkflowItem[]>('/workflows'),create:(data:Partial<WorkflowItem>)=>http.post<never,WorkflowItem>('/workflows',data),detail:(id:number)=>http.get<never,WorkflowItem>(`/workflows/${id}`),update:(id:number,data:Partial<WorkflowItem>)=>http.put<never,WorkflowItem>(`/workflows/${id}`,data),remove:(id:number)=>http.delete(`/workflows/${id}`),saveNodes:(id:number,nodes:WorkflowNodeDto[])=>http.put(`/workflows/${id}/nodes`,nodes),run:(id:number)=>http.post<never,{runId:number}>(`/workflows/${id}/run`),runs:(id:number)=>http.get<never,RunItem[]>(`/workflows/${id}/runs`),schedule:(id:number)=>http.get<never,{cron?:string;nextFireTime?:string}>(`/workflows/${id}/schedule`)}
