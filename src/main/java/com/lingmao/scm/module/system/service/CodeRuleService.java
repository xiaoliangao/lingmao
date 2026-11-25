package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeRuleVO;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;

import java.util.List;

/**
 * 编码规则（RFC 018 §4）：款号、物料编码、供应商编码怎么编，管理后台能改。
 * 只负责"按哪条规则、拼成什么样"；取流水、"被占了再取下一个"在各自的域里（它们才查得到自己的表）
 */
public interface CodeRuleService {

    /** 这种编码的全部规则：通用规则在最前，别的按排序 */
    List<CodeRuleVO> list(CodeTarget target);

    Long create(CodeRuleSaveReq req);

    void update(long id, CodeRuleSaveReq req);

    /** 通用规则不能删 */
    void delete(long id);

    /**
     * 按 context 的 scopeValue 找适用的规则（写了它的那条，没有用通用的），把流水以外的段拼好。
     * 规则里某一段取不到值（没选设计师、品类没填编码……）抛 BizException，说清楚缺什么
     */
    CodeTemplate resolve(CodeTarget target, CodeContext context);
}
