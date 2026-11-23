package com.lingmao.scm.module.fg.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ReferrerProvider;
import com.lingmao.scm.module.fg.entity.FgDoc;
import com.lingmao.scm.module.fg.entity.FgDocType;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 引用检查不能把无权限的有效下游过滤成“没有引用”；也不能泄漏其单号和数量。 */
@Component
@RequiredArgsConstructor
class FgReferrers implements ReferrerProvider {
    private final FgDocMapper docMapper;

    @Override
    public List<DocRef> referrers(BizType type, long id) {
        Long companyId;
        if (type == BizType.PRODUCTION_RECEIPT) {
            var source = docMapper.receiptHead(id); companyId = source == null ? null : source.companyId();
        } else if (type == BizType.ORDER) {
            var source = docMapper.orderHead(id); companyId = source == null ? null : source.companyId();
        } else return List.of();
        if (companyId == null) return List.of();
        CompanyContext.target(companyId);
        long count = docMapper.selectCount(source(type, id, companyId));
        if (count == 0) return List.of();
        var scoped = source(type, id, companyId);
        DataScope.current(false).apply(scoped, FgDoc::getCreateBy);
        if (!LoginUser.hasAuthority("fg:doc:list") || docMapper.selectCount(scoped) != count)
            throw new BizException(ErrorCodes.HAS_DOWNSTREAM, "存在当前账号不可查看的有效成品单据，需先由有权限的人员处理");
        if (count > 100) throw more(type);
        var rows = docMapper.selectList(scoped.orderByAsc(FgDoc::getId).last("LIMIT 101"));
        if (rows.size() > 100) throw more(type);
        return rows.stream().map(FgReferrers::ref).toList();
    }

    private static BizException more(BizType type) {
        String filter = type == BizType.PRODUCTION_RECEIPT ? "receiptId" : "orderId";
        return new BizException(ErrorCodes.HAS_DOWNSTREAM, "有效成品引用超过 100 张，请在成品单据列表按 " + filter + " 分页查看并先处理");
    }

    private static LambdaQueryWrapper<FgDoc> source(BizType type, long id, long companyId) {
        var q = new LambdaQueryWrapper<FgDoc>().eq(FgDoc::getCompanyId, companyId).ne(FgDoc::getStatus, DocStatus.VOIDED);
        if (type == BizType.PRODUCTION_RECEIPT) return q.eq(FgDoc::getDocType, FgDocType.RECEIPT_IN).eq(FgDoc::getPrdReceiptId, id);
        return q.eq(FgDoc::getDocType, FgDocType.SHIP_OUT).eq(FgDoc::getOrderId, id);
    }

    @Override
    public Map<Long, DocRef> describe(BizType type, Collection<Long> ids) {
        if (type != BizType.FG_DOC || ids == null || ids.isEmpty()) return Map.of();
        if (!LoginUser.hasAuthority("fg:doc:list")) throw new BizException(404, "成品单据不存在");
        List<Long> wanted = FgScope.ids(ids, 500, "成品引用");
        var q = new LambdaQueryWrapper<FgDoc>().in(FgDoc::getId, wanted);
        Long company = CompanyContext.header(false);
        if (company != null) q.eq(FgDoc::getCompanyId, company);
        DataScope.current(false).apply(q, FgDoc::getCreateBy);
        List<FgDoc> docs = docMapper.selectList(q.orderByAsc(FgDoc::getId).last("LIMIT 500"));
        if (docs.size() != wanted.size()) throw new BizException(404, "成品单据不存在");
        Map<Long, DocRef> result = new LinkedHashMap<>(); docs.forEach(d -> result.put(d.getId(), ref(d)));
        return result;
    }

    private static DocRef ref(FgDoc d) {
        return DocRef.of(BizType.FG_DOC, d.getId(), d.getNo(), d.getStatus(), StatusLabels.DOC, d.getCreateBy())
                .withKind(d.getKind().name()).withCompany(d.getCompanyId());
    }
}
