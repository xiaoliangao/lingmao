package com.lingmao.scm.module.board.dto;

import lombok.Data;

/**
 * 待办筛选（docs/api/06 §2.3）：mine 进一步按当前负责人收窄；只有 ALL 可以选择 ownerId，SELF 忽略 ownerId。
 * "待我审核"一组和审核类的数不受它影响
 */
@Data
public class HomeTodoQuery {
    /** 可选：FG 待入库按这次请求选中的主体计数，须匹配 X-Company-Id。 */
    private Long companyId;
    /** true：只看负责人是我的 */
    private boolean mine;
    /** 只看负责人是这个人的（生产跟单、采购员……），和 mine 二选一 */
    private Long ownerId;
}
