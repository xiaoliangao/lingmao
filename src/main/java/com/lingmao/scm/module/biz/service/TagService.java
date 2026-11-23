package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.TagArchiveReq;
import com.lingmao.scm.module.biz.dto.TagObjectVO;
import com.lingmao.scm.module.biz.dto.TagQuery;
import com.lingmao.scm.module.biz.dto.TagRenameReq;
import com.lingmao.scm.module.biz.dto.TagReplaceReq;
import com.lingmao.scm.module.biz.dto.TagVO;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import java.util.List;

public interface TagService {
    List<TagVO> options(TagQuery query);
    TagObjectVO read(TagObjectType type, long id);
    /** 审计等只读守卫复用的实时鉴权；不读取标签关联或缓存载荷。 */
    void checkReadable(TagObjectType type, long id);
    TagObjectVO replace(TagObjectType type, long id, TagReplaceReq request);
    TagVO rename(long id, TagRenameReq request);
    TagVO archive(long id, TagArchiveReq request);
    PageResult<DocLogVO> logs(long id, PageQuery query);
    void authorizeReplaceReplay(TagObjectType type, long id);
    void authorizeLibraryReplay(long id);
}
