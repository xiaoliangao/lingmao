package com.lingmao.scm.module.basic.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.ColorQuery;
import com.lingmao.scm.module.basic.dto.ColorSaveReq;
import com.lingmao.scm.module.basic.entity.Color;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 颜色（RFC 003 §5.2）：编码 2-3 位、转大写、唯一，建好不改；名称唯一；名称、色系、色块能改 */
public interface ColorService {

    PageResult<Color> page(ColorQuery query);

    Color getById(long id);

    /** 下拉、选色：启用的，按编码排 */
    List<ColorOption> options();

    Long create(ColorSaveReq req);

    void update(long id, ColorSaveReq req);

    void delete(long id);

    void enable(long id);

    void disable(long id);

    /** 一批颜色，id → 颜色，停用的也给；查不到的不在结果里。物料拼 SKU 码、显示颜色名用 */
    Map<Long, Color> byIds(Collection<Long> ids);
}
