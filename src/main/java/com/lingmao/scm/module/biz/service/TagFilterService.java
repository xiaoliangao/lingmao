package com.lingmao.scm.module.biz.service;

import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import com.lingmao.scm.module.biz.dto.TagQuery;
import com.lingmao.scm.module.biz.dto.TagVO;
import java.util.List;

/** 准备有界、可信部署的标签交集条件；对象权限仍由调用领域的原查询执行。 */
public interface TagFilterService {
    void prepare(TaggedPageQuery query);
    List<TagVO> options(TagQuery query);
}
