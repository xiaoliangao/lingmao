package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.ColorQuery;
import com.lingmao.scm.module.basic.dto.ColorSaveReq;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.basic.service.ColorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ColorServiceImpl implements ColorService {

    static final String FAMILY_DICT = "color_family";

    private final ColorMapper colorMapper;
    private final DictChecks dictChecks;

    @Override
    public PageResult<Color> page(ColorQuery query) {
        String kw = Texts.trim(query.getKeyword());
        LambdaQueryWrapper<Color> wrapper = new LambdaQueryWrapper<Color>()
                .and(kw != null, w -> w.apply("upper(code) LIKE {0}", Keywords.codeContains(kw))
                        .or().apply("name ILIKE {0}", Keywords.contains(kw, 32)))
                .eq(query.getStatus() != null, Color::getStatus, query.getStatus())
                .eq(Texts.trim(query.getFamily()) != null, Color::getFamily, query.getFamily())
                .orderByAsc(Color::getCode);
        return PageResult.of(colorMapper.selectPage(query.toPage(), wrapper));
    }

    @Override
    public Color getById(long id) {
        Color color = colorMapper.selectById(id);
        if (color == null) {
            throw new BizException(404, "颜色不存在: " + id);
        }
        return color;
    }

    @Override
    public List<ColorOption> options() {
        return colorMapper.selectList(new LambdaQueryWrapper<Color>()
                        .eq(Color::getStatus, Statuses.ENABLED)
                        .orderByAsc(Color::getCode))
                .stream().map(ColorOption::from).toList();
    }

    @Override
    @Transactional
    public Long create(ColorSaveReq req) {
        String code = Texts.upper(req.getCode());
        if (!code.matches("[A-Z]{2}")) {
            // 甲方 11-18：颜色编码进物料、款式的 SKU 码，只能 2 位字母（RFC 012 §5.3）；领猫导进来的老编码照收，不走这里
            throw new BizException("颜色编码只能是 2 位字母，如 KQ");
        }
        if (colorMapper.exists(new LambdaQueryWrapper<Color>().eq(Color::getCode, code))) {
            throw new BizException("颜色编码 " + code + " 已经有了");
        }
        Color color = new Color();
        color.setCode(code);
        fill(color, req, null);
        colorMapper.insert(color);
        return color.getId();
    }

    @Override
    @Transactional
    public void update(long id, ColorSaveReq req) {
        Color color = getById(id);
        if (!color.getCode().equals(Texts.upper(req.getCode()))) {
            throw new BizException("颜色编码建好就不能改：编码进了 SKU 码。录错了而且没用过的，删掉重建");
        }
        fill(color, req, color);
        colorMapper.update(color, Updates.replaceById(color));
    }

    @Override
    @Transactional
    public void delete(long id) {
        getById(id);
        colorMapper.deleteById(id);   // 被物料 SKU、款式颜色引用了，外键拦住 → 1005
    }

    @Override
    @Transactional
    public void enable(long id) {
        Statuses.set(colorMapper, Color::new, id, Statuses.ENABLED, "颜色");
    }

    @Override
    @Transactional
    public void disable(long id) {
        Statuses.set(colorMapper, Color::new, id, Statuses.DISABLED, "颜色");
    }

    @Override
    public Map<Long, Color> byIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return colorMapper.selectByIds(ids).stream().collect(Collectors.toMap(Color::getId, Function.identity()));
    }

    /** 名称唯一（排除自己）；色系查字典。old 是修改前的（新建传 null），用来放过没改的停用色系 */
    private void fill(Color color, ColorSaveReq req, Color old) {
        String name = Texts.trim(req.getName());
        boolean nameTaken = colorMapper.exists(new LambdaQueryWrapper<Color>()
                .eq(Color::getName, name)
                .ne(old != null, Color::getId, old == null ? null : old.getId()));
        if (nameTaken) {
            throw new BizException("颜色名称「" + name + "」已经有了");
        }
        String family = Texts.trim(req.getFamily());
        dictChecks.require(FAMILY_DICT, family, old == null ? null : old.getFamily(), "色系");
        color.setName(name);
        color.setFamily(family);
        color.setHex(Texts.upper(req.getHex()));
    }
}
