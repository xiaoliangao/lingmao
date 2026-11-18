package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.module.basic.dto.SizeGroupCreateReq;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;

import java.util.List;
import java.util.Collection;
import java.util.Map;

/**
 * 尺码组（RFC 003 §5.3）：新建时定全部尺码和基码（默认取中间那个），之后只能在末尾追加尺码，不能删、改、调顺序，基码不能换；
 * 名称能改。写错了而且没被款式用过的，整组删掉重建
 */
public interface SizeGroupService {

    /** 全部，停用的也在，按名称排 */
    List<SizeGroupVO> list();

    /** 下拉：启用的 */
    List<SizeGroupVO> options();

    SizeGroupVO getById(long id);

    /** 款式批量展开矩阵时一次取组头、一次取尺码；缺失引用仍报 404。 */
    Map<Long, SizeGroupVO> getByIds(Collection<Long> ids);

    Long create(SizeGroupCreateReq req);

    void rename(long id, String name);

    /** 追加到末尾。和已有的重复、或者这次填的互相重复，报 1000 */
    void appendSizes(long id, String sizes);

    /** 连同尺码一起删；被款式引用了外键拦住 → 1005，整个回滚 */
    void delete(long id);

    void enable(long id);

    void disable(long id);
}
