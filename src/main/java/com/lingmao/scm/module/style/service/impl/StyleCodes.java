package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.CodeRuleService;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 款号（RFC 005 §3、架构 §4.5、RFC 018 §4）。自动编号按管理后台 › 编码规则 里适用于这个大类的规则编，
 * 预置的通用规则是 大类 + yyMMdd + 设计师编号 + 2 位流水，如 FS2611080701：
 * 预览只看不取（peek），保存时才在建款的事务里真取（nextSeq）；取到的号已经被手填的款式占了就再取下一个。
 * 规则里有"设计师编号"时，第一次用某个设计师编号编款号锁住它（UserService.lockDesignerNo），之后用户页不能再改。
 * 手填的转大写、查格式和重复，不推流水
 */
@Component
@RequiredArgsConstructor
class StyleCodes {

    /** 手填款号的格式（原型的规则，RFC 005 §13 第 5 条）；导入的老款号不受它限制 */
    static final Pattern MANUAL = Pattern.compile("^[A-Z0-9-]{3,20}$");

    static final String USED_UP = "今天的流水用完了，请手填款号";

    /** 品牌的值本身能直接当编码用的：字母、数字、横杠 */
    private static final Pattern BRAND_VALUE = Pattern.compile("^[A-Za-z0-9-]+$");
    static final String DUPLICATE = "款号重复";

    private final StyleMapper styleMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final UserService userService;
    private final CodeRuleService codeRuleService;
    private final CategoryService categoryService;
    private final StyleDicts dicts;

    /** 自动编号用到的款式字段：规则里有哪一段才用哪一个 */
    record Input(String styleClass, Long designerId, String brand, Long categoryId, Integer year) {
    }

    /** 按规则拼好的样子，和取到的设计师（规则里没有设计师编号是 null） */
    private record Resolved(CodeTemplate template, UserVO designer) {
    }

    /** 预览：当前流水 + 1，跳过已经有款式用了的号。不加锁、不占号，点几次都一样 */
    String preview(Input input) {
        CodeTemplate t = resolve(input).template();
        for (int seq = serialNoGenerator.peek(t.key()); seq <= t.maxSeq(); seq++) {
            String code = t.format(seq);
            if (!exists(code)) {
                return code;
            }
        }
        throw new BizException(USED_UP);
    }

    /**
     * 真取号，要在建款的事务里：sys_serial 这一行锁到提交，同一前缀的建款排队，号不重复。
     * 取到的号已经有了（手填的）就再取；流水用完 → 1000。设计师编号还没锁的，这时锁上：
     * 编号刚被管理员改了（取号用的是旧号）→ 1002，整个建款回滚
     */
    String take(Input input) {
        Resolved resolved = resolve(input);
        CodeTemplate t = resolved.template();
        String code = null;
        for (int tries = 0; code == null; tries++) {
            int seq = serialNoGenerator.nextSeq(t.key());
            if (seq > t.maxSeq() || tries > t.maxSeq()) {
                throw new BizException(USED_UP);   // 抛出去整个建款回滚，这次 +1 也撤销
            }
            String candidate = t.format(seq);
            if (!exists(candidate)) {
                code = candidate;
            }
        }
        UserVO designer = resolved.designer();
        if (designer != null && !Boolean.TRUE.equals(designer.designerNoLocked())) {
            userService.lockDesignerNo(designer.id(), designer.designerNo());
        }
        return code;
    }

    /** 手填：转大写，3-20 位字母、数字、横杠；已经有了 → 1000"款号重复"（唯一索引兜底） */
    String manual(String text) {
        String code = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            throw new BizException(400, "请填款号，或者改成自动编号");
        }
        if (!MANUAL.matcher(code).matches()) {
            throw new BizException(400, "款号只能是 3-20 位的字母、数字和横杠");
        }
        if (exists(code)) {
            throw new BizException(DUPLICATE);
        }
        return code;
    }

    boolean exists(String code) {
        return styleMapper.exists(new LambdaQueryWrapper<Style>().eq(Style::getCode, code));
    }

    /** 按适用于这个大类的规则拼；规则里没有的段不去取（没用设计师编号的规则不要求选设计师） */
    private Resolved resolve(Input in) {
        if (in.styleClass() == null || in.styleClass().isBlank()) {
            throw new BizException(400, "自动编号要先选大类：按大类找编码规则");
        }
        String styleClass = in.styleClass().strip();
        UserVO[] designer = new UserVO[1];
        CodeContext context = CodeContext.of(styleClass)
                .with(CodeSegment.Type.STYLE_CLASS, () -> styleClass)
                .with(CodeSegment.Type.BRAND, () -> brandCode(in.brand()))
                .with(CodeSegment.Type.TOP_CATEGORY, () -> topCategoryCode(in.categoryId()))
                .with(CodeSegment.Type.CATEGORY, () -> categoryCode(in.categoryId()))
                .with(CodeSegment.Type.STYLE_YEAR, () -> in.year() == null ? null : String.format("%02d", Math.floorMod(in.year(), 100)))
                .with(CodeSegment.Type.DESIGNER, () -> {
                    designer[0] = designer(in.designerId());
                    return designer[0].designerNo();
                });
        CodeTemplate template = codeRuleService.resolve(CodeTarget.STYLE, context);
        return new Resolved(template, designer[0]);
    }

    /**
     * 品牌编码（RFC 018 §8.3）：字典「品牌」上填的编码（text_value）；没填的、值本身是字母数字的用值（以前就这样）。
     * 导入款式时建的品牌，值是中文名、又不能改，所以另给一列编码
     */
    private String brandCode(String brand) {
        if (brand == null || brand.isBlank()) {
            throw new BizException("编码规则里有品牌编码，要先选品牌；也可以手填款号");
        }
        String value = brand.strip();
        DictOption option = dicts.options(StyleDicts.BRAND).stream().filter(o -> o.value().equals(value)).findFirst().orElse(null);
        if (option != null && option.textValue() != null && !option.textValue().isBlank()) {
            return option.textValue();
        }
        if (!BRAND_VALUE.matcher(value).matches()) {
            throw new BizException("品牌「" + (option == null ? value : option.label())
                    + "」还没填编码，编码规则里要用它：请超级管理员在 管理后台 › 系统管理 › 字典管理 › 品牌 填上；也可以手填款号");
        }
        return value;
    }

    private String categoryCode(Long categoryId) {
        if (categoryId == null) {
            throw new BizException("编码规则里有品类编码，要先选品类；也可以手填款号");
        }
        Category category = categoryService.getById(categoryId);
        if (category.getCode() == null || category.getCode().isBlank()) {
            throw new BizException("品类「" + category.getName() + "」还没填编码，编码规则里要用它：去 资料 › 品类 填上，或者手填款号");
        }
        return category.getCode();
    }

    /** 品类往上找到一级；款式选的就是一级的，用它自己 */
    private String topCategoryCode(Long categoryId) {
        if (categoryId == null) {
            throw new BizException("编码规则里有一级品类编码，要先选品类；也可以手填款号");
        }
        Category top = categoryService.getById(categoryId);
        for (int depth = 0; top.getParentId() != null && depth < 5; depth++) {
            top = categoryService.getById(top.getParentId());
        }
        if (top.getCode() == null || top.getCode().isBlank()) {
            throw new BizException("一级品类「" + top.getName() + "」还没填编码，编码规则里要用它：去 资料 › 基础 › 品类 填上，或者手填款号");
        }
        return top.getCode();
    }

    private UserVO designer(Long designerId) {
        if (designerId == null) {
            throw new BizException("自动编号要先选设计师：款号里有设计师编号。也可以手填款号");
        }
        UserVO designer = userService.getById(designerId);
        if (designer.designerNo() == null) {
            throw new BizException("设计师" + designer.nickname() + "还没有设计师编号，请超级管理员在 管理后台 › 用户 里填；也可以手填款号");
        }
        return designer;
    }
}
