package com.lingmao.scm;

import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeRuleVO;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.service.CodeRuleService;
import com.lingmao.scm.module.system.service.impl.CodeRuleServiceImpl;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 单测用的编码规则：只有 V57 预置的三条通用规则，拼法用真的 CodeRuleServiceImpl.render。
 * 款号 = 大类 + yyMMdd + 设计师编号 + 2 位流水，物料 = 分类码 + 4 位，供应商 = GYS + 4 位（RFC 018 §4.4）
 */
public final class TestCodeRules implements CodeRuleService {

    private final Clock clock;

    public TestCodeRules(Clock clock) {
        this.clock = clock;
    }

    public static List<CodeSegment> presetSegments(CodeTarget target) {
        return switch (target) {
            case STYLE -> List.of(seg(CodeSegment.Type.STYLE_CLASS), new CodeSegment(CodeSegment.Type.DATE, null, "yyMMdd", null),
                    seg(CodeSegment.Type.DESIGNER), new CodeSegment(CodeSegment.Type.SEQ, null, null, 2));
            case MATERIAL -> List.of(seg(CodeSegment.Type.MAT_CATEGORY), new CodeSegment(CodeSegment.Type.SEQ, null, null, 4));
            case SUPPLIER -> List.of(new CodeSegment(CodeSegment.Type.TEXT, "GYS", null, null),
                    new CodeSegment(CodeSegment.Type.SEQ, null, null, 4));
        };
    }

    private static CodeSegment seg(CodeSegment.Type type) {
        return new CodeSegment(type, null, null, null);
    }

    @Override
    public CodeTemplate resolve(CodeTarget target, CodeContext context) {
        return CodeRuleServiceImpl.render(target, "通用规则", presetSegments(target), context, LocalDate.now(clock));
    }

    @Override
    public List<CodeRuleVO> list(CodeTarget target) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long create(CodeRuleSaveReq req) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void update(long id, CodeRuleSaveReq req) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void delete(long id) {
        throw new UnsupportedOperationException();
    }
}
