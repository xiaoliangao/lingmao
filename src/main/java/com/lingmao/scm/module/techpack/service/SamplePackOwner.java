package com.lingmao.scm.module.techpack.service;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.dto.PackOwner;

/** TechPack 定义能力，dev 提供真实样衣 owner；不存在从 techpack 回调 SampleService 的环。 */
public interface SamplePackOwner {
    record Reference(Long id,String code,Long ownerId) { }
    /** 轻量批量引用投影，整批实际 owner 授权，任一隐藏/不存在均拒绝。 */
    java.util.Map<Long,Reference> references(long companyId,java.util.Collection<Long> sampleIds);
    enum Access { READ, EDIT, CONVERT }
    record Owned(long companyId,long sampleId,int sampleVersionLock,PackOwner owner) { }
    /** EDIT/CONVERT 必须加入已有事务，先真实样衣、当前单 FOR UPDATE，再由调用方锁 TP。 */
    Owned resolve(long companyId,long sampleId,Access access);
    /** 审计和文件的纯授权守卫：不读完整资料或缓存，普通 dev:list + 当前父 SELF。 */
    void checkReadable(long companyId,long sampleId);
    AttachmentVO mainImage(long companyId,long sampleId);
}
