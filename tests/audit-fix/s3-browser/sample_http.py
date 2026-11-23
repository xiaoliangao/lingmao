"""Strict synthetic API09 transport used by samples.py; never validates backend business rules."""
import asyncio
import base64
from copy import deepcopy
from datetime import date, timedelta
import re
from urllib.parse import parse_qs, urlsplit

SAMPLE = '9007199254743001'
ORDER = '9007199254743101'
PACK = '9007199254743201'
STYLE = '9007199254743301'
USER = '9007199254740993'
STAMP = '2026-11-22T12:00:00+08:00'
COLORS = [{'id':'21','code':'BK','name':'黑色','hex':'#202020'}, {'id':'22','code':'BL','name':'雾蓝','hex':'#9aafc4'}]
SIZES = ['M','L']
NODE_NAMES = [('RECEIVE','接单',1),('PATTERN','纸样',2),('CUT','裁剪',1),('SEW','车版',3),('SEND','送样',1)]
IMAGE = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')


def nodes(need='2026-12-10', done=False):
    cursor = date.fromisoformat(need); out=[]
    for index, (code,name,duration) in reversed(list(enumerate(NODE_NAMES))):
        out.append({'id':str(9007199254743401+index),'seq':index+1,'nodeCode':code,'nodeName':name,'durationDays':duration,
                    'planDate':cursor.isoformat(),'doneDate':'2026-11-21' if done else None,'doneBy':USER if done else None,
                    'doneByName':'合成版师' if done else None,'ownerId':USER,'ownerName':'合成版师','remark':None,
                    'state':'DONE' if done else 'RUNNING' if index==0 else 'TODO'})
        cursor -= timedelta(days=duration)
    return list(reversed(out))


def order(version=1, state='WAIT_ASSIGN'):
    return {'id': str(int(ORDER)+version-1),'sampleId':SAMPLE,'companyId':'1','no':'DY-SYN-'+str(version).zfill(3),
            'version':3,'sampleVersionLock':7,'sampleVersion':version,'sampleType':'FIRST' if version==1 else 'REVISE',
            'current':True,'status':state,'makeWay':None if state=='WAIT_ASSIGN' else 'EXTERNAL',
            'factoryId':None if state=='WAIT_ASSIGN' else '31','factoryName':None if state=='WAIT_ASSIGN' else '合成打样厂',
            'makerId':None,'makerName':None,'needDate':'2026-12-10','qty':1,'sizeGroupId':'12','colors':deepcopy(COLORS),
            'sizeCodes':SIZES[:],'reason':None if version==1 else '领型调整','requirement':'按研发资料包核对尺寸',
            'nodes':[] if state=='WAIT_ASSIGN' else nodes(done=state in {'WAIT_REVIEW','PASSED'}), 'review':None,
            'actions':{}}


def sample(state='WAIT_ASSIGN', versions=1):
    current = order(versions,state)
    return {'id':SAMPLE,'code':'YY-SYN-001','companyId':'1','version':7,'name':'合成雾蓝衬衫样衣','brand':'LM','categoryId':'11',
            'categoryName':'女装 / 上装','year':2027,'season':'SPRING','wave':'A','designerId':USER,'designerName':'合成设计师',
            'patternMakerId':USER,'patternMakerName':'合成版师','source':'OWN','customerId':None,'customerStyleNo':None,
            'sizeGroupId':'12','sizeGroupName':'合成尺码组','colorIds':['21','22'],'colors':deepcopy(COLORS), 'sizes':SIZES[:],
            'baseSize':'M','sampleFactoryId':'31','sampleFactoryName':'合成打样厂','fabricSupplierId':'32','fabricSupplierName':'合成面料商',
            'fabricDesc':'合成测试棉布','expectFinishDate':'2026-12-10','estTagPrice':'199.99','estCost':'45.6789',
            'remark':'仅用于合成 HTTP 组件验证','status':'PASSED' if state=='PASSED' else 'ELIMINATED' if state=='ELIMINATED' else 'DEVELOPING',
            'showStatus':state,'images':[],'currentOrder':current,'convertedStyleId':None,'convertedStyleCode':None,
            'convertBy':None,'convertTime':None,'createBy':USER,'createTime':STAMP,'updateTime':STAMP,'actions':{}}


def pack(version=1, state='DRAFT'):
    return {'id':str(int(PACK)+version-1),'styleId':None,'sampleId':SAMPLE,'companyId':'1','styleCode':'YY-SYN-001',
            'styleName':'合成雾蓝衬衫样衣','aliasName':None,'stage':'DEV','version':version,'versionLock':4,'status':state,
            'versionNote':None,'sourcePackId':None,'sourceVersion':None,'sizes':SIZES[:],'baseSize':'M','colors':deepcopy(COLORS),
            'pointOptions':[{'label':'胸围','method':'平量胸部','value':'胸围'}], 'specPoints':[], 'bomItems':[], 'craftSections':[],
            'processes':[],'stdSecondsTotal':0,'actions':['edit','publish','remove'] if state=='DRAFT' else ['revise'],
            'contextSource':'LIVE' if state=='DRAFT' else 'SNAPSHOT','utilPct':100,'packingMode':None,
            'createByName':'合成设计师','createTime':STAMP,'updateTime':STAMP,'publishByName':None,'publishTime':None}


def row(detail):
    d=deepcopy(detail); o=d.pop('currentOrder')
    d.update(image=None,currentOrderId=o['id'],currentOrderNo=o['no'],currentOrderVersion=o['version'],currentSampleVersion=o['sampleVersion'],
             sampleType=o['sampleType'],orderStatus=o['status'],makeWay=o['makeWay'],factoryId=o['factoryId'],factoryName=o['factoryName'],
             makerId=o['makerId'],makerName=o['makerName'],qty=o['qty'],needDate=o['needDate'],
             currentNode=next(({'id':n['id'],'code':n['nodeCode'],'name':n['nodeName'],'planDate':n['planDate'],'ownerId':n['ownerId'],'ownerName':n['ownerName'],'state':n['state']} for n in o['nodes'] if not n['doneDate']),None),late=False)
    return d


class SampleHttp:
    def __init__(self,state='WAIT_ASSIGN',versions=1,packs=0):
        self.value=sample(state,versions)
        self.orders=[deepcopy(self.value['currentOrder'])]+[order(v,'REVISE') for v in range(versions-1,0,-1)]
        self.packs=[pack(v,'DRAFT' if v==packs else 'VOIDED') for v in range(packs,0,-1)]
        self.calls=[];self.attempts=[];self.writes=[];self.errors=[];self.expected_resources=[];self.aborted=[]
        self.receipts={};self.faults={};self.holds={};self.tasks=set();self.lose_after_commit=set();self.hidden_after_write=False
        self.hidden=False;self.files=[];self.tags={'version':0,'tags':[]};self.convert_code_error=None
        self.total=65;self.suppress_cost=False;self.void_colors=False
        self.home_pack_only=False;self.home_deliveries=False;self.home_backup=False
        self.sync()

    def sync(self):
        d=self.value;o=self.orders[0]
        state=d['status'];live=state=='DEVELOPING'
        d['showStatus']='CONVERTED' if state=='CONVERTED' else 'ELIMINATED' if state=='ELIMINATED' else 'PASSED' if state=='PASSED' else o['status']
        d['actions']={'edit':state in {'DEVELOPING','PASSED'},'remove':live and o['sampleVersion']==1 and o['status']=='WAIT_ASSIGN' and not self.packs,
                      'stop':state=='DEVELOPING','reopen':state in {'PASSED','ELIMINATED'},'convert':state=='PASSED'}
        for current in self.orders:
            current['sampleVersionLock']=d['version'];current['companyId']=d['companyId'];current['current']=current is o
            current['actions']={'edit':current is o and live and current['status'] in {'WAIT_ASSIGN','MAKING'},
                                'dispatch':current is o and live and current['status']=='WAIT_ASSIGN',
                                'node':current is o and live and current['status'] in {'MAKING','WAIT_REVIEW'},
                                'review':current is o and live and current['status']=='WAIT_REVIEW'}
        d['currentOrder']=deepcopy(o)

    def detail(self):
        self.sync();d=deepcopy(self.value)
        if self.suppress_cost:d.pop('estCost',None)
        return d

    def hold(self,path,keyword=None):
        value={key:asyncio.Event() for key in ['started','release','finished']}
        self.holds[(path,keyword)]=value;return value

    async def fault(self,route,code):
        status=code if code in {400,403,404,409,500,503} else 200
        if status>=400:self.expected_resources.append({'path':urlsplit(route.request.url).path,'status':status})
        await route.fulfill(status=status,json={'code':code,'msg':'合成受控失败，请重读核对','data':None},headers={'X-Request-Id':'sample-synthetic-fault'})

    async def route(self,route):
        task=asyncio.current_task();self.tasks.add(task)
        req=route.request;path=urlsplit(req.url).path;params=parse_qs(urlsplit(req.url).query);method=req.method
        call={'method':method,'path':path,'params':params,'company':req.headers.get('x-company-id'),'requestId':req.headers.get('x-request-id')}
        self.calls.append(call)
        body=req.post_data_json if method in {'POST','PUT','PATCH'} and path!='/api/files' and req.post_data else None
        key=req.headers.get('idempotency-key');held=self.holds.get((path,params.get('keyword',[None])[0]))
        if held and held['started'].is_set():held=None
        scoped=path.startswith('/api/samples') or path.startswith('/api/sample-orders')
        sample_write=scoped and method!='GET'
        if sample_write:self.attempts.append({**call,'body':deepcopy(body),'key':key})
        try:
            assert call['requestId'],call
            if path=='/api/admin/backup-status':
                assert method=='GET' and not params and self.home_backup
                assert await req.frame.evaluate("async()=>{const{useUserStore}=await import('/src/stores/user.ts');return useUserStore().isAdmin}")
            if path=='/api/board/home/kpi' and self.home_pack_only:
                assert await req.frame.evaluate("async()=>{const{useUserStore}=await import('/src/stores/user.ts');const user=useUserStore();return user.hasPerm('techpack:pack:list')&&!user.hasPerm('style:style:list')&&!user.hasPerm('dev:sample:list')}")
            if scoped:
                company=body['companyId'] if path=='/api/samples' and method=='POST' else params.get('companyId',[None])[0]
                assert company in {'1','2'} and call['company']==company,call
            if path.startswith('/api/tech-packs') and ('sampleId' in params or path!='/api/tech-packs' or method=='POST'):
                assert call['company']==self.value['companyId'],call
            if sample_write:
                assert re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}',key or ''),call
            if held:
                held['started'].set();await asyncio.wait_for(held['release'].wait(),20)
            queue=self.faults.get((method,path),[])
            if queue:await self.fault(route,queue.pop(0));return
            if self.hidden and scoped and path not in {'/api/samples','/api/samples/counts'} and method=='GET':await self.fault(route,404);return
            receipt_key=(method,path,key)
            if sample_write and receipt_key in self.receipts:data=deepcopy(self.receipts[receipt_key])
            elif method=='GET':data=self.read(path,params)
            elif sample_write:
                data=self.write(path,params,body)
                if data is False:await self.fault(route,1002);return
                self.writes.append({**call,'body':deepcopy(body),'key':key})
                self.receipts[receipt_key]=deepcopy(data)
                if self.hidden_after_write:self.hidden=True
                if path in self.lose_after_commit:
                    self.lose_after_commit.remove(path);self.faults[(method,path)]=[503,503];await self.fault(route,503);return
            elif path.startswith('/api/tech-packs'):
                assert re.fullmatch(r'[0-9a-f-]{36}',key or '') or path.endswith('/bom-defaults')
                data=self.pack_write(path,method,body)
                self.writes.append({**call,'body':deepcopy(body),'key':key})
            elif path=='/api/files':
                assert method=='POST' and req.headers['content-type'].startswith('multipart/form-data;')
                assert b'filename="sample-proof.png"' in req.post_data_buffer and IMAGE in req.post_data_buffer
                data={'url':'/files/sample-proof.png','thumbUrl':None,'name':'sample-proof.png','size':len(IMAGE)}
            elif re.fullmatch(r'/api/tags/objects/(SAMPLE|TECH_PACK)/\d+/replace',path):
                assert call['company']==self.value['companyId'];assert method=='POST'
                assert set(body)=={'version','names','retainIds','tagVersions'} and body['version']==self.tags['version']
                self.tags={'version':self.tags['version']+1,'tags':[{'id':str(9007199254746001+i),'name':name.lstrip('#'),'archived':False,'version':0} for i,name in enumerate(body['names'])]};data=deepcopy(self.tags)
                self.writes.append({**call,'body':deepcopy(body),'key':key})
            else:raise AssertionError('Unexpected synthetic write '+path)
            if path.endswith('/pdf'):
                await route.fulfill(body=b'%PDF-1.4\nsynthetic',content_type='application/pdf',headers={'Content-Disposition':"attachment; filename*=UTF-8''sample.pdf"});return
            await route.fulfill(json={'code':0,'msg':'','data':data},headers={'X-Request-Id':'sample-synthetic-response'})
        except asyncio.CancelledError:raise
        except Exception as error:
            self.errors.append(type(error).__name__+': '+str(error))
            try:await route.fulfill(status=500,json={'code':500,'msg':'synthetic contract mismatch','data':None})
            except Exception:pass
        finally:
            if held:held['finished'].set()
            self.tasks.discard(task)

    def read(self,path,p):
        if path=='/api/samples':
            assert set(p)<={'companyId','page','size','keyword','status','categoryId','year','season','brand','designerId','factoryId','makerId','nodeCode','late','needFrom','needTo','createdFrom','createdTo','sort'},p
            page=int(p.get('page',['1'])[0]);size=int(p.get('size',['20'])[0]);assert 1<=size<=100
            if self.hidden:return {'list':[],'total':0}
            status=p.get('status',[None])[0];total=18 if status=='MAKING' else self.total
            source=sample('MAKING') if status=='MAKING' else self.detail()
            rows=[]
            for i in range((page-1)*size,min(total,page*size)):
                value=row(source);value['id']=str(int(SAMPLE)+i);value['code']='YY-SYN-'+str(i+1).zfill(3);rows.append(value)
            return {'list':rows,'total':total}
        if path=='/api/samples/counts':return dict(ALL=0,WAIT_ASSIGN=0,MAKING=0,WAIT_REVIEW=0,PASSED=0,CONVERTED=0,ELIMINATED=0) if self.hidden else dict(ALL=self.total,WAIT_ASSIGN=22,MAKING=18,WAIT_REVIEW=10,PASSED=10,CONVERTED=3,ELIMINATED=2)
        if path=='/api/samples/'+SAMPLE:return self.detail()
        if path=='/api/samples/'+SAMPLE+'/orders':
            before=int(p.get('beforeVersion',['2147483647'])[0]);size=int(p.get('size',['20'])[0]);assert 1<=size<=100
            self.sync();chosen=[deepcopy(o) for o in self.orders if o['sampleVersion']<before];page=chosen[:size]
            return {'list':page,'hasMore':len(chosen)>size,'nextBeforeVersion':page[-1]['sampleVersion'] if len(chosen)>size else None}
        if path=='/api/samples/'+SAMPLE+'/logs':return {'list':[{'id':'9007199254744501','action':'dispatch','fromStatus':'WAIT_ASSIGN','toStatus':'MAKING','remark':'合成日志 · 不含价格','operatorId':USER,'operatorName':'合成设计师','createTime':STAMP}],'total':1}
        if path=='/api/samples/'+SAMPLE+'/references':return []
        if path=='/api/samples/by-style/'+STYLE:return {'id':SAMPLE,'code':self.value['code'],'name':self.value['name'],'companyId':self.value['companyId']}
        if path.endswith('/dispatch-preview') and path.startswith('/api/sample-orders/'):
            return {'nodes':[{key:value for key,value in n.items() if key in {'seq','nodeCode','nodeName','durationDays','planDate'}} for n in nodes(p['needDate'][0])]}
        if path.startswith('/api/sample-orders/') and path.endswith('/attachments'):return deepcopy(self.files)
        if path=='/api/samples/'+SAMPLE+'/convert-preview':
            return {'code':None if self.convert_code_error else 'KS-SYN-001','codeError':self.convert_code_error,'skuCount':len(self.value['colors'])*len(self.value['sizes']),
                    'devPack':next(({'id':x['id'],'version':x['version'],'status':'EFFECTIVE'} for x in self.packs if x['status']=='EFFECTIVE'),None)}
        if path=='/api/tech-packs':
            if 'styleId' in p:return {'styleId':STYLE,'packs':[],'defaultPackId':None,'creatableStages':['DEV','PROD'],'unmatchedColors':[]}
            assert set(p)<={'sampleId','companyId','beforeVersion','size'} and p['sampleId']==[SAMPLE]
            before=int(p.get('beforeVersion',['2147483647'])[0]);size=int(p.get('size',['20'])[0]);selected=[deepcopy(x) for x in self.packs if x['version']<before];page=selected[:size]
            return {'styleId':None,'sampleId':SAMPLE,'companyId':self.value['companyId'],'packs':page,'defaultPackId':self.packs[0]['id'] if self.packs else None,
                    'creatableStages':[] if self.packs else ['DEV'],'unmatchedColors':[],'unitCost':None,'unitCostPackId':None,'unitCostStage':None,
                    'total':len(self.packs),'hasMore':len(selected)>size,'nextBeforeVersion':page[-1]['version'] if len(selected)>size else None}
        if re.fullmatch(r'/api/tech-packs/\d+',path):return deepcopy(next(x for x in self.packs if x['id']==path.split('/')[-1]))
        if path.startswith('/api/tech-packs/') and path.endswith('/cost'):
            return {'techPackId':path.split('/')[3],'baseSize':'M','colorId':'21','costMode':'OUTSOURCE','buyPrice':None,'buyPriceDefault':None,'defaultFactoryId':None,'defaultFactoryName':None,'fabricRows':[],'trimRows':[],'laborTotal':0,'processCount':0,'otherItems':[],'tagPrice':199.99,'ratio':None,'mixedBasis':False,
                    'summary':{'fabric':0,'trim':0,'labor':0,'other':0,'total':0},'dev':None}
        if path.startswith('/api/tech-packs/') and path.endswith('/logs'):return []
        if path.endswith('/pdf'):return None
        if re.fullmatch(r'/api/tags/objects/(SAMPLE|TECH_PACK)/\d+',path):return deepcopy(self.tags)
        if path=='/api/tags':return []
        if path.startswith('/api/dict-data/by-type/'):
            kind=path.split('/')[-1]
            values={'brand':[('LM','合成品牌')],'season':[('SPRING','春季')],'wave':[('A','首波')],
                    'style_source':[('OWN','自主开发'),('SELECTED','选款'),('CUSTOMER_SAMPLE','客户来样')],
                    'style_class':[('GARMENT','成衣'),('ACCESSORY','配饰')],'unit':[('M','米'),('PCS','件')],
                    'sample_node':[(code,name) for code,name,_ in NODE_NAMES],'bom_part':[('MAIN','主料')]}.get(kind,[])
            return [{'id':str(i+1),'value':value,'label':label,'status':1,'sort':i,'extra':None} for i,(value,label) in enumerate(values)]
        if path=='/api/companies/options':return [{'id':'1','shortName':'合成主体甲'},{'id':'2','shortName':'合成主体乙'}]
        if path=='/api/categories/tree':return [{'id':'11','name':'上装','children':[],'status':1,'parentId':None,'sort':1}]
        if path=='/api/size-groups/options':return [{'id':'12','name':'合成尺码组','sizes':SIZES[:],'baseSize':'M','status':1}]
        if path=='/api/colors/options':return deepcopy(COLORS)
        if path=='/api/users/options':return [{'id':USER,'nickname':'合成版师','username':'synthetic-maker'},{'id':'9007199254740994','nickname':'合成评审人','username':'synthetic-reviewer'}]
        if path=='/api/suppliers/options':
            assert set(p)<={'keyword','category','usage','includeId'},p
            values=[{'id':'31','code':'GC-SYN','name':'合成打样厂','shortName':'合成打样厂','status':'ACTIVE','sampleQualified':True},
                    {'id':'32','code':'ML-SYN','name':'合成面料商','shortName':'合成面料商','status':'ACTIVE','sampleQualified':True}]
            return [v for v in values if not p.get('includeId') or v['id']==p['includeId'][0]]
        if path=='/api/version':return {'version':'synthetic-samples','buildTime':None}
        if path=='/api/board/home/kpi':
            if self.home_pack_only:return {'dev':{'styleCount':None,'packDraftCount':3,'sampleMakingCount':None,'companyId':None},'order':None,'production':None,'material':None}
            return {'dev':{'styleCount':None if self.suppress_cost else 8,'packDraftCount':None if self.suppress_cost else 3,'sampleMakingCount':18,'companyId':p.get('companyId',[None])[0]},'order':None,'production':None,'material':None}
        if path=='/api/board/home/todos':
            if self.home_pack_only:return {'canSeeAll':False,'groups':[],'counts':[]}
            company=p.get('companyId',[None])[0]
            return {'canSeeAll':False,'groups':[{'key':'SAMPLE_REVIEW','total':25,'items':[{'ref':self.ref(company),'title':'合成样衣待评审','sub':'第 1 版','image':None}]}] if company else [],
                    'counts':[{'key':'SAMPLE_REVIEW','count':25,'companyId':company},{'key':'SAMPLE_LATE','count':7,'companyId':company}] if company else []}
        if path=='/api/board/home/deliveries':return [{'ref':{**self.ref(),'type':'ORDER','typeLabel':'需求订单','no':'DD-HOME-SYN-001'},'sub':'合成近期交期','image':None,'dueDate':'2026-11-26','daysLeft':3,'doneQty':2,'totalQty':10}] if self.home_deliveries else []
        if path=='/api/admin/backup-status' and self.home_backup:return {'configured':True,'latestFile':'synthetic-backup.sql','latestTime':STAMP,'sizeBytes':1024,'stale':False}
        if path=='/api/styles/'+STYLE:
            return {'id':STYLE,'code':'KS-SYN-001','name':self.value['name'],'status':'ENABLED','styleClass':'GARMENT','brand':'LM','categoryId':'11','categoryName':'上装','categoryPath':'女装/上装','year':2027,'season':'SPRING','source':'OWN','sizeGroupId':'12','sizeGroupName':'合成尺码组','sizes':SIZES[:],'baseSize':'M','colors':[],'images':[],'files':[],'actions':[],'legacyAttrs':{},'techPackStatus':'NONE','skuCount':4,'createTime':STAMP,'updateTime':STAMP}
        if path=='/api/styles/'+STYLE+'/skus':return []
        if path=='/api/styles/'+STYLE+'/files':return []
        if path=='/api/tags/objects/STYLE/'+STYLE:return {'version':0,'tags':[]}
        raise AssertionError('Unexpected synthetic GET '+path+' '+str(p))

    def ref(self,company='1',kind='SAMPLE'):
        return {'type':kind,'id':SAMPLE,'kind':None,'companyId':company,'parentId':None,'typeLabel':'样衣','no':self.value['code'],'status':'WAIT_REVIEW','statusLabel':'待评审','statusTone':'pending','ownerId':USER,'ownerName':'合成设计师'}

    def receipt(self,warning=None):
        self.sync();d=self.value;o=self.orders[0]
        return {'sampleId':d['id'],'companyId':d['companyId'],'sampleVersionLock':d['version'],'orderId':o['id'],'version':o['version'],'sampleVersion':o['sampleVersion'],'sampleStatus':d['status'],'orderStatus':o['status'],'warning':warning}

    def write(self,path,p,b):
        d=self.value;o=self.orders[0];warning=None
        if path=='/api/samples':
            assert set(b)<={'companyId','name','brand','categoryId','year','season','wave','designerId','patternMakerId','source','customerId','customerStyleNo','sizeGroupId','colorIds','sampleFactoryId','fabricSupplierId','fabricDesc','expectFinishDate','estTagPrice','estCost','remark','needDate','images'},b
            assert b['colorIds'] and all(isinstance(x,str) for x in b['colorIds'])
            assert b.get('estTagPrice') is None or isinstance(b['estTagPrice'],str)
            assert 'estCost' not in b or b['estCost'] is None or isinstance(b['estCost'],str)
            assert b.get('customerId') is None and b.get('customerStyleNo') is None and b['source']!='CUSTOMER_SAMPLE'
            d.update({k:v for k,v in b.items() if k!='needDate'});d['colors']=[deepcopy(c) for c in COLORS if c['id'] in b['colorIds']]
            o['needDate']=b['needDate'];o['colors']=[deepcopy(d['colors'][0])];o['sizeCodes']=[d['baseSize']];self.sync()
            return {'id':SAMPLE,'code':d['code'],'companyId':d['companyId'],'version':d['version'],'currentOrderId':o['id'],'currentOrderVersion':o['version']}
        if path.startswith('/api/samples/'):
            locks=int(p['version'][0]) if b is None else b['version']
            if locks!=d['version']:return False
            if path=='/api/samples/'+SAMPLE and b is None:return {'id':SAMPLE,'companyId':d['companyId'],'removed':True}
            d['version']+=1
            if path=='/api/samples/'+SAMPLE:
                assert not set(b)&{'status','currentOrder','convertedStyleId','tenantId'}
                d.update({k:v for k,v in b.items() if k!='version'});d['colors']=[deepcopy(c) for c in COLORS if c['id'] in b['colorIds']]
            elif path.endswith('/images'):
                assert set(b)=={'version','files'};d['images']=[{'id':str(9007199254744101+i),**f} for i,f in enumerate(b['files'])]
                return {'id':SAMPLE,'companyId':d['companyId'],'version':d['version'],'files':deepcopy(d['images'])}
            elif path.endswith('/stop'):
                assert set(b)=={'version','reason'} and b['reason'];d['status']='ELIMINATED';o['status']='CANCELLED';o['version']+=1
            elif path.endswith('/reopen'):
                assert set(b)=={'version','reason'} and b['reason'];d['status']='DEVELOPING';next_order=order(o['sampleVersion']+1);next_order['reason']=b['reason'];self.orders.insert(0,next_order)
                warning='原选色黑色已失效，新版本仅保留雾蓝' if self.void_colors else None
                if self.void_colors:self.orders[0]['colors']=[deepcopy(COLORS[1])]
            elif path.endswith('/convert'):
                assert set(b)<={'version','codeMode','code','styleClass','name','aliasName','defaultFactoryId'}
                d['status']='CONVERTED';d['convertedStyleId']=STYLE;d['convertedStyleCode']=b.get('code') or 'KS-SYN-001'
                return {'styleId':STYLE,'styleCode':d['convertedStyleCode'],'skuCount':len(d['colors'])*len(d['sizes']),'prodPackId':None,'companyId':d['companyId'],'sampleVersionLock':d['version']}
            else:raise AssertionError(path)
        else:
            identifier=path.split('/')[3];assert identifier==o['id'],(identifier,o['id'])
            locks={'version':int(p['version'][0]),'sampleVersionLock':int(p['sampleVersionLock'][0])} if b is None else b
            if locks['version']!=o['version'] or locks['sampleVersionLock']!=d['version']:return False
            o['version']+=1;d['version']+=1
            if path.endswith('/dispatch'):
                assert set(b)<={'version','sampleVersionLock','makeWay','factoryId','makerId','needDate'}
                assert b['factoryId'] is None if b['makeWay']=='INTERNAL' else b['makerId'] is None
                o.update({k:v for k,v in b.items() if k not in {'version','sampleVersionLock'}});o['status']='MAKING';o['nodes']=nodes(b['needDate']);o['factoryName']='合成打样厂' if b['makeWay']=='EXTERNAL' else None;o['makerName']='合成版师' if b['makeWay']=='INTERNAL' else None
            elif path.endswith('/review'):
                assert set(b)<={'version','sampleVersionLock','result','opinion','reviewDate','reviewerIds','photos'} and 1<=len(b['reviewerIds'])<=100
                assert b['result']=='PASS' or b['opinion']
                o['review']={'id':'9007199254744901','reviewDate':b['reviewDate'],'result':b['result'],'opinion':b['opinion'],'reviewers':[{'id':x,'name':'合成评审人'} for x in b['reviewerIds']],'photos':b['photos']}
                if b['result']=='PASS':o['status']='PASSED';d['status']='PASSED'
                elif b['result']=='ELIMINATE':o['status']='ELIMINATED';d['status']='ELIMINATED'
                else:
                    o['status']='REVISE';n=order(o['sampleVersion']+1);n['reason']=b['opinion'];self.orders.insert(0,n)
                    if self.void_colors:n['colors']=[deepcopy(COLORS[1])];warning='原选色黑色已失效，新版本仅保留雾蓝'
            elif path.endswith('/nodes'):
                assert set(b)=={'version','sampleVersionLock','nodes'} and {n['id'] for n in b['nodes']}=={n['id'] for n in o['nodes']}
                for change in b['nodes']:
                    assert set(change)=={'id','planDate','ownerId','remark'}
                    next(n for n in o['nodes'] if n['id']==change['id']).update(change)
            elif path.endswith('/done'):
                node=next(n for n in o['nodes'] if n['id']==path.split('/')[-2])
                node['doneDate']=None if b is None else b['doneDate'];node['state']='TODO' if b is None else 'DONE'
                if node['nodeCode']=='SEND':
                    o['status']='MAKING' if b is None else 'WAIT_REVIEW'
                    if b and any(not n['doneDate'] for n in o['nodes'] if n['nodeCode']!='SEND'):warning='已送样；接单、纸样等前置节点尚未报完成'
            elif path.endswith('/attachments'):
                assert set(b)=={'version','sampleVersionLock','files'};self.files=[{'id':str(9007199254744101+i),**f} for i,f in enumerate(b['files'])]
                return {**self.receipt(),'files':deepcopy(self.files)}
            else:
                assert set(b)<={'version','sampleVersionLock','sampleType','qty','colorIds','sizeCodes','needDate','reason','requirement','makeWay','factoryId','makerId'}
                assert isinstance(b['qty'],int) and 1<=b['qty']<=99999999
                assert o['sampleVersion']==1 or b.get('reason','').strip()
                for k,v in b.items():
                    if k not in {'version','sampleVersionLock','colorIds'}:o[k]=deepcopy(v)
                o['colors']=[deepcopy(c) for c in COLORS if c['id'] in b['colorIds']]
        return self.receipt(warning)

    def pack_write(self,path,method,body):
        if path=='/api/tech-packs':
            assert set(body)=={'sampleId','companyId','stage'} and body['sampleId']==SAMPLE and body['stage']=='DEV';p=pack(len(self.packs)+1);self.packs.insert(0,p);return p['id']
        p=next(x for x in self.packs if x['id']==path.split('/')[3])
        if method=='DELETE':self.packs.remove(p);return None
        if path.endswith('/bom-defaults'):return []
        if path.endswith('/publish'):
            assert body=={'versionLock':p['versionLock']};p['status']='EFFECTIVE';p['actions']=['revise'];return None
        if path.endswith('/revise'):
            new=pack(len(self.packs)+1);self.packs.insert(0,new);return new['id']
        assert method=='PUT' and body['versionLock']==p['versionLock'];p['versionLock']+=1
        for k,v in body.items():
            if k not in {'versionLock','cost'}:p[k]=deepcopy(v)
        return p['versionLock']
