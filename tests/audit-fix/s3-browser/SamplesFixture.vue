<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getToken, setToken, setSessionSubject } from '@/utils/token'
import type { MenuNode } from '@/types/system'
const user=useUserStore(), route=useRoute(), router=useRouter()
const all=['dev:sample:list','dev:sample:add','dev:sample:edit','dev:sample:remove','dev:sample:make','dev:sample:review','dev:sample:convert',
 'techpack:pack:list','techpack:pack:add','techpack:pack:edit','techpack:pack:publish','techpack:pack:remove','techpack:cost:query',
 'biz:tag:list','style:style:list','data:all']
const permissions=new URLSearchParams(location.search).get('permissions')
const perms=permissions===null?all:permissions?permissions.split(','):[]
const menu=(id:string,name:string,path:string,component:string):MenuNode=>({id,name,path,component,parentId:'0',type:2,perms:null,icon:'Scissor',sort:10,visible:true,status:1,adminOnly:false,children:[]})
user.info={id:'9007199254740993',username:'synthetic-sample',nickname:'合成设计师',roles:(new URLSearchParams(location.search).get('roles')??'').split(',').filter(Boolean),perms,
 menus:[...(perms.includes('dev:sample:list')?[menu('301','样衣','/samples','dev/sample/index')]:[]),...(perms.includes('style:style:list')?[menu('302','款式','/styles','style/style/index')]:[])],defaultCompany:{id:'1',name:'合成主体甲'}}
setSessionSubject(user.info.id)
function identity(){setToken('synthetic-sample-next-user');user.token=getToken();if(user.info)user.info={...user.info,id:'9007199254740994'};setSessionSubject('9007199254740994')}
function company(){if(user.info)user.info={...user.info,defaultCompany:{id:'2',name:'合成主体乙'}}}
function role(kind:string){if(user.info)user.info={...user.info,perms:kind==='none'?[]:kind==='read'?all.filter(p=>p.endsWith(':list')||p==='data:all'):all}}
function scope(){if(user.info)user.info={...user.info,roles:['synthetic-self'],perms:user.info.perms.filter(p=>p!=='data:all')}}
function tokenRefresh(){setToken('synthetic-sample-refreshed-access',true);user.token=getToken()}
const currentPath=computed(()=>route.fullPath)
</script>
<template><router-view/><aside class="sample-fixture-controls"><span data-testid="sample-path">{{currentPath}}</span><button data-testid="sample-identity" @click="identity">切换身份</button><button data-testid="sample-company" @click="company">切换主体</button><button data-testid="sample-readonly" @click="role('read')">只读权限</button><button data-testid="sample-no-permission" @click="role('none')">撤销读取</button><button data-testid="sample-full-permission" @click="role('full')">恢复权限</button><button data-testid="sample-scope" @click="scope">范围收窄</button><button data-testid="sample-refresh" @click="tokenRefresh">同会话刷新</button><button data-testid="sample-home" @click="router.push('/home')">首页</button></aside></template>
<style scoped>.sample-fixture-controls{position:fixed;bottom:2px;right:2px;z-index:5000;display:flex;gap:8px;padding:2px;background:var(--lm-paper);font-size:10px;color:var(--lm-muted)}button{border:0;background:none;color:inherit;font:inherit;cursor:pointer}</style>
