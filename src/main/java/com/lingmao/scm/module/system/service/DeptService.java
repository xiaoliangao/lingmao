package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.dto.DeptVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 部门（RFC 018 §1）：一棵树，最多 5 级，人和部门多对多 */
public interface DeptService {

    /** 整棵树，同级按排序、id 排；每个部门带直属人数和全路径 */
    List<DeptVO> tree();

    Long create(DeptSaveReq req);

    /** 改名、换上级、排序；不能换到自己或自己的下级下面，换过去不能超过 5 级 */
    void update(long id, DeptSaveReq req);

    /** 下面还有部门、还有人的不能删（1005） */
    void delete(long id);

    /** 这个部门和它所有下级的 id；部门不存在返回空表 */
    List<Long> withDescendants(long id);

    /** 部门 id 都在（新建、修改用户时查）；有不在的抛 400 */
    void requireExisting(Collection<Long> ids);

    /** id → 全路径"产品部/设计组" */
    Map<Long, String> paths();
}
