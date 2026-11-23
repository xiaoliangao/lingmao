/** API09：样衣新增金额是十进制字符串，ID 不转 Number，纯日期不转 UTC。 */
import type { ColorOption } from './basic'
import type { Attachment } from './biz'
import type { NodeState } from './production'
import type { FileVO } from './system'
export type AttachmentInput = Pick<FileVO, 'url' | 'name'> & { thumbUrl?: string | null; size?: number | null }

export type Id = string
export type Money = string
export type DateOnly = string
export type SampleStatus = 'DEVELOPING'|'PASSED'|'CONVERTED'|'ELIMINATED'
export type SampleOrderStatus = 'WAIT_ASSIGN'|'MAKING'|'WAIT_REVIEW'|'PASSED'|'REVISE'|'ELIMINATED'|'CANCELLED'
export type SampleShowStatus = 'WAIT_ASSIGN'|'MAKING'|'WAIT_REVIEW'|'PASSED'|'CONVERTED'|'ELIMINATED'
export type SampleType = 'FIRST'|'REVISE'|'FULL_SET'|'PRE_PRODUCTION'
export type MakeWay = 'INTERNAL'|'EXTERNAL'
export type ReviewResult = 'PASS'|'REVISE'|'ELIMINATE'
export interface NamedId { id:Id; name:string }
export interface SampleQuery {
  /** 同时包含全部所选公共标签；最多 30 个稳定 ID。 */
  tagIds?: string[]
  companyId:Id; page?:number; size?:number; keyword?:string; status?:SampleShowStatus;
  categoryId?:Id; year?:number; season?:string; brand?:string; designerId?:Id;
  factoryId?:Id; makerId?:Id; nodeCode?:string; late?:boolean;
  needFrom?:DateOnly; needTo?:DateOnly; createdFrom?:DateOnly; createdTo?:DateOnly;
  sort?:'CREATED_DESC'|'CREATED_ASC'|'NEED_ASC'|'NEED_DESC'|'CODE_ASC'|'CODE_DESC';
}
export type SampleCounts = Record<SampleShowStatus|'ALL',number>
export interface SampleFields {
  companyId:Id; name:string; brand:string; categoryId:Id; year:number; season:string;
  wave?:string|null; designerId?:Id|null; patternMakerId?:Id|null; source:string;
  customerId?:Id|null; customerStyleNo?:string|null; sizeGroupId:Id; colorIds:Id[];
  sampleFactoryId?:Id|null; fabricSupplierId?:Id|null; fabricDesc?:string|null;
  expectFinishDate?:DateOnly|null; estTagPrice?:Money|null; estCost?:Money|null; remark?:string|null;
}
export interface SampleCreateForm extends SampleFields { needDate:DateOnly; images:AttachmentInput[] }
export interface SampleUpdateForm extends SampleFields { version:number }
export interface SampleCreated { id:Id; code:string; companyId:Id; version:number; currentOrderId:Id; currentOrderVersion:number }
export interface SampleRef { id:Id; code:string; name:string; companyId:Id }
export interface SampleActionResult {
  sampleId:Id; companyId:Id; sampleVersionLock:number;
  orderId:Id; version:number; sampleVersion:number;
  sampleStatus:SampleStatus; orderStatus:SampleOrderStatus; warning:string|null;
}
export interface SampleRemoved { id:Id; companyId:Id; removed:true }
export interface SampleImagesResult { id:Id; companyId:Id; version:number; files:Attachment[] }
export interface SampleOrderFilesResult extends SampleActionResult { files:Attachment[] }
export interface SampleRow {
  id:Id; code:string; companyId:Id; version:number; name:string; image:Attachment|null;
  brand:string; categoryId:Id; categoryName:string; year:number; season:string; wave:string|null;
  designerId:Id|null; designerName:string|null; patternMakerId:Id|null; patternMakerName:string|null;
  source:string; fabricDesc:string|null; estTagPrice:Money|null; estCost?:Money|null;
  status:SampleStatus; showStatus:SampleShowStatus; convertedStyleId:Id|null; convertedStyleCode:string|null;
  currentOrderId:Id; currentOrderNo:string; currentOrderVersion:number; currentSampleVersion:number;
  sampleType:SampleType; orderStatus:SampleOrderStatus; makeWay:MakeWay|null;
  factoryId:Id|null; factoryName:string|null; makerId:Id|null; makerName:string|null;
  qty:number; needDate:DateOnly; currentNode:SampleCurrentNode|null; late:boolean;
  createTime:string;
}
export interface SampleCurrentNode { id:Id; code:string; name:string; planDate:DateOnly; ownerId:Id|null; ownerName:string|null; state:NodeState }
export interface SampleDetail extends SampleFields {
  id:Id; code:string; version:number; status:SampleStatus; showStatus:SampleShowStatus;
  categoryName:string; designerName:string|null; patternMakerName:string|null;
  sampleFactoryName:string|null; fabricSupplierName:string|null;
  colors:ColorOption[]; sizes:string[]; baseSize:string; sizeGroupName:string;
  images:Attachment[]; currentOrder:SampleOrder;
  convertedStyleId:Id|null; convertedStyleCode:string|null; convertBy:Id|null; convertTime:string|null;
  createBy:Id; createTime:string; updateTime:string;
  actions:{edit:boolean;remove:boolean;stop:boolean;reopen:boolean;convert:boolean};
}
export interface SampleVersionPage { list:SampleOrder[]; hasMore:boolean; nextBeforeVersion:number|null }
export interface SampleOrder {
  id:Id; sampleId:Id; companyId:Id; no:string; version:number; sampleVersionLock:number;
  sampleVersion:number; sampleType:SampleType; current:boolean; status:SampleOrderStatus;
  makeWay:MakeWay|null; factoryId:Id|null; factoryName:string|null; makerId:Id|null; makerName:string|null;
  needDate:DateOnly; qty:number; sizeGroupId:Id; colors:ColorOption[]; sizeCodes:string[];
  reason:string|null; requirement:string|null; nodes:SampleNode[]; review:SampleReview|null;
  actions:{edit:boolean;dispatch:boolean;node:boolean;review:boolean};
}
export interface SampleNode {
  id:Id; seq:number; nodeCode:string; nodeName:string; durationDays:number;
  planDate:DateOnly; doneDate:DateOnly|null; doneBy:Id|null; doneByName:string|null;
  ownerId:Id|null; ownerName:string|null; remark:string|null; state:NodeState;
}
export interface SampleReview { id:Id; reviewDate:DateOnly; result:ReviewResult; opinion:string|null; reviewers:NamedId[]; photos:Attachment[] }
export interface SampleOrderForm {
  version:number; sampleVersionLock:number; sampleType:SampleType; qty:number;
  colorIds:Id[]; sizeCodes:string[]; needDate:DateOnly; reason?:string|null; requirement?:string|null;
  makeWay?:MakeWay|null; factoryId?:Id|null; makerId?:Id|null;
}
export interface DispatchForm { version:number; sampleVersionLock:number; makeWay:MakeWay; factoryId?:Id|null; makerId?:Id|null; needDate:DateOnly }
export interface PlannedNode { seq:number; nodeCode:string; nodeName:string; durationDays:number; planDate:DateOnly }
export interface NodePlanForm { version:number; sampleVersionLock:number; nodes:{id:Id;planDate:DateOnly;ownerId:Id|null;remark:string|null}[] }
export interface NodeDoneForm { version:number; sampleVersionLock:number; doneDate:DateOnly }
export interface ReviewForm { version:number;sampleVersionLock:number;result:ReviewResult;opinion?:string|null;reviewDate:DateOnly;reviewerIds:Id[];photos:AttachmentInput[] }
export interface ConvertPreview { code:string|null; codeError:string|null; skuCount:number; devPack:{id:Id;version:number;status:'EFFECTIVE'}|null }
export interface ConvertForm { version:number;codeMode:'AUTO'|'MANUAL';code?:string|null;styleClass:string;name:string;aliasName?:string|null;defaultFactoryId?:Id|null }
export interface ConvertResult { styleId:Id;styleCode:string;skuCount:number;prodPackId:Id|null;companyId:Id;sampleVersionLock:number }
