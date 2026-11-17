package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.common.doc.DocStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 一种单据的状态怎么叫、标签什么颜色（docs/api/README §4.1）：库里存英文名，中文按单据叫——采购单的 RUNNING 叫"部分到货"，
 * 需求订单的 VOIDED 叫"已取消"。做 DocRef 的一方（实现 ReferrerProvider 的那个域）用它把状态翻好，和前端各模块的 XXX_STATUS 一致。
 * <pre>
 * // 用通用状态、只改几个叫法的：在 DOC 上改
 * static final StatusLabels&lt;DocStatus&gt; PO_STATUS = StatusLabels.DOC.rename(RUNNING, "部分到货").rename(DONE, "已到齐");
 * // 自己有状态枚举的：一个一个写
 * static final StatusLabels&lt;MrpStatus&gt; MRP_STATUS = StatusLabels.of(MrpStatus.class)
 *         .with(MrpStatus.DRAFT, "草稿", StatusTone.OFF).with(MrpStatus.CONFIRMED, "已确认", StatusTone.ACTIVE)…;
 * DocRef.of(BizType.PURCHASE_ORDER, po.getId(), po.getNo(), po.getStatus(), PO_STATUS, po.getBuyerId())
 * </pre>
 * 不可变：with、rename 返回新的一份，放 static final 里多个线程一起用
 */
public final class StatusLabels<S extends Enum<S>> {

    /** 一个状态的叫法和颜色 */
    public record Label(String text, StatusTone tone) {
    }

    /** 通用状态的默认叫法和颜色，和前端 types/biz.ts 的 DOC_STATUS 一样 */
    public static final StatusLabels<DocStatus> DOC = of(DocStatus.class)
            .with(DocStatus.DRAFT, DocStatus.DRAFT.getLabel(), StatusTone.OFF)
            .with(DocStatus.PENDING, DocStatus.PENDING.getLabel(), StatusTone.PENDING)
            .with(DocStatus.APPROVED, DocStatus.APPROVED.getLabel(), StatusTone.ACTIVE)
            .with(DocStatus.RUNNING, DocStatus.RUNNING.getLabel(), StatusTone.PROGRESS)
            .with(DocStatus.DONE, DocStatus.DONE.getLabel(), StatusTone.DONE)
            .with(DocStatus.CLOSED, DocStatus.CLOSED.getLabel(), StatusTone.CLOSED)
            .with(DocStatus.VOIDED, DocStatus.VOIDED.getLabel(), StatusTone.VOID);

    private final Class<S> type;
    private final Map<S, Label> labels;

    private StatusLabels(Class<S> type, Map<S, Label> labels) {
        this.type = type;
        this.labels = Collections.unmodifiableMap(labels);
    }

    /** 开始写一种状态枚举的叫法，接着 with(...) 一个一个加 */
    public static <S extends Enum<S>> StatusLabels<S> of(Class<S> type) {
        return new StatusLabels<>(type, new EnumMap<>(type));
    }

    /** 加（或者换掉）一个状态的叫法和颜色，返回新的一份 */
    public StatusLabels<S> with(S status, String text, StatusTone tone) {
        Map<S, Label> copy = new EnumMap<>(type);
        copy.putAll(labels);
        copy.put(status, new Label(text, tone));
        return new StatusLabels<>(type, copy);
    }

    /** 只改叫法、颜色不变（采购单的 RUNNING 还是 PROGRESS，只是叫"部分到货"） */
    public StatusLabels<S> rename(S status, String text) {
        return with(status, text, label(status).tone());
    }

    /** 某个状态的叫法和颜色。表里没写这个状态是程序的错（漏写了），不悄悄给个英文名 */
    public Label label(S status) {
        Label label = labels.get(status);
        if (label == null) {
            throw new IllegalStateException(type.getSimpleName() + " 的状态 " + status + " 没写叫法");
        }
        return label;
    }
}
