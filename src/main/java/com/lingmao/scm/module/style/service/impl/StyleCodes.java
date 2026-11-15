package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 款号（RFC 005 §3、架构 §4.5）。自动编号 = 大类 + yyMMdd + 设计师编号 + 2 位流水，如 FS2611080701：
 * 预览只看不取（peek），保存时才在建款的事务里真取（next）；取到的号已经被手填的款式占了就再取下一个。
 * 第一次用某个设计师编号编款号时锁住它（UserService.lockDesignerNo），之后用户页不能再改。
 * 手填的转大写、查格式和重复，不推流水
 */
@Component
@RequiredArgsConstructor
class StyleCodes {

    /** 款号末尾的流水 2 位：一个设计师一天最多 99 款 */
    static final int SEQ_WIDTH = 2;
    static final int MAX_SEQ = 99;
    /** 手填款号的格式（原型的规则，RFC 005 §13 第 5 条）；导入的老款号不受它限制 */
    static final Pattern MANUAL = Pattern.compile("^[A-Z0-9-]{3,20}$");

    static final String USED_UP = "今天的流水用完了，请手填款号";
    static final String DUPLICATE = "款号重复";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final StyleMapper styleMapper;
    private final SerialNoGenerator serialNoGenerator;
    private final UserService userService;
    private final Clock clock;

    /** 预览：当前流水 + 1，跳过已经有款式用了的号。不加锁、不占号，点几次都一样 */
    String preview(String styleClass, Long designerId) {
        String key = key(styleClass, designer(designerId).designerNo());
        for (int seq = serialNoGenerator.peek(key); seq <= MAX_SEQ; seq++) {
            String code = SerialNoGenerator.format(key, seq, SEQ_WIDTH);
            if (!exists(code)) {
                return code;
            }
        }
        throw new BizException(USED_UP);
    }

    /**
     * 真取号，要在建款的事务里：sys_serial 这一行锁到提交，同一设计师同一天的建款排队，号不重复。
     * 取到的号已经有了（手填的）就再取；超过 99 → 1000。设计师编号还没锁的，这时锁上：
     * 编号刚被管理员改了（取号用的是旧号）→ 1002，整个建款回滚
     */
    String take(String styleClass, Long designerId) {
        UserVO designer = designer(designerId);
        String key = key(styleClass, designer.designerNo());
        String code = null;
        for (int tries = 0; code == null; tries++) {
            if (tries > MAX_SEQ) {
                throw new BizException(USED_UP);
            }
            String candidate;
            try {
                candidate = serialNoGenerator.next(key, SEQ_WIDTH);
            } catch (BizException e) {
                throw new BizException(USED_UP);   // 流水超过 2 位
            }
            if (!exists(candidate)) {
                code = candidate;
            }
        }
        if (!Boolean.TRUE.equals(designer.designerNoLocked())) {
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

    /** 流水的 key：FS + 261108 + 07 */
    private String key(String styleClass, String designerNo) {
        if (styleClass == null || styleClass.isBlank()) {
            throw new BizException(400, "自动编号要先选大类：款号开头是大类");
        }
        return styleClass.strip() + LocalDate.now(clock).format(DAY) + designerNo;
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
