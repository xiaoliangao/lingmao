package com.lingmao.scm.module.system.dto;


import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class DictDataQuery extends PageQuery {
    /**
     * 分页参数 page、size 和它们的校验（size 最多 500，防止一次拉几十万行）写在父类里，每个列表接口都继承它，前端传参的方式全系统统一。
     * 两个筛选字段可以为空：为空表示"不按这个条件筛"。Service 里会根据它们是不是空，决定要不要拼进 WHERE。
     * 为什么是 GET 参数而不是 JSON：查询不改数据，用 GET。GET 的参数在 URL 上（?typeCode=season&page=1），Spring 会按字段名自动填进这个对象。
     */
    private String typeCode;
    private String label;
}
