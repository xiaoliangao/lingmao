package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.service.CodeRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 物料编码（RFC 012 §4.2、RFC 018 §4）：按管理后台 › 编码规则 里适用于这个物料类型的规则编，
 * 预置的通用规则是 物料分类码 + 4 位流水（ML0012），和改成规则以前一样。新建、导入都走这里。
 * 取到的号已经有了（11-18 以前导入的照用领猫编码）就再取下一个，最多试 100 次。
 * 先查后插在同一个事务里：sys_serial 这一行的锁持有到提交，同一前缀的新建本来就排队；和导入撞上由唯一索引兜底（1000）
 */
@Component
@RequiredArgsConstructor
class MaterialCodes {

    static final int MAX_TRIES = 100;

    private final SerialNoGenerator serialNoGenerator;
    private final MaterialMapper materialMapper;
    private final CodeRuleService codeRuleService;

    /** 真取号，要在新建、导入的事务里 */
    String next(String materialType, String categoryCode) {
        CodeTemplate t = template(materialType, categoryCode);
        for (int i = 0; i < MAX_TRIES; i++) {
            int seq = serialNoGenerator.nextSeq(t.key());
            if (seq > t.maxSeq()) {
                throw new BizException("编码规则「" + t.ruleName() + "」的流水用完了（" + t.width() + " 位），请超级管理员在 管理后台 › 编码规则 里给流水加一位");
            }
            String code = t.format(seq);
            if (!materialMapper.exists(new LambdaQueryWrapper<Material>().eq(Material::getCode, code))) {
                return code;
            }
        }
        throw new BizException("编码 " + t.format(0) + " 这一组连着 " + MAX_TRIES + " 个都被占了，请联系管理员");
    }

    /** 预览：下一个大概是几，只看不取；被占了的跳过。保存时以真取到的为准 */
    String preview(String materialType, String categoryCode) {
        CodeTemplate t = template(materialType, categoryCode);
        int start = serialNoGenerator.peek(t.key());
        for (int seq = start; seq <= t.maxSeq() && seq < start + MAX_TRIES; seq++) {
            String code = t.format(seq);
            if (!materialMapper.exists(new LambdaQueryWrapper<Material>().eq(Material::getCode, code))) {
                return code;
            }
        }
        throw new BizException("编码规则「" + t.ruleName() + "」的流水用完了，请超级管理员给流水加一位");
    }

    private CodeTemplate template(String materialType, String categoryCode) {
        return codeRuleService.resolve(CodeTarget.MATERIAL, CodeContext.of(materialType)
                .with(CodeSegment.Type.MAT_CATEGORY, () -> categoryCode));
    }
}
