package com.lingmao.scm.common.doc;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import org.junit.jupiter.api.Test;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 状态转换表（架构 §4.2）：允许的过，不允许的 1002，表写错、调错是程序的错 */
class StatusFlowTest {

    /** 架构 §4.2 那张通用的表，一行不差：各单据以后照这个样子挑自己要的行 */
    private static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(APPROVED)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("start", APPROVED).to(RUNNING)
            .on("finish", RUNNING).to(DONE)
            .on("close", APPROVED, RUNNING).to(CLOSED)
            .on("reopen", CLOSED).to(APPROVED, RUNNING, DONE)
            .on("void", DRAFT, PENDING).to(VOIDED)
            .build();

    /** 规则：表里允许的，返回做完的状态；一个动作几个起点的，每个起点都行 */
    @Test
    void next_allowed_returnsTarget() {
        assertThat(FLOW.next("submit", DRAFT)).isEqualTo(PENDING);
        assertThat(FLOW.next("audit", PENDING)).isEqualTo(APPROVED);
        assertThat(FLOW.next("close", APPROVED)).isEqualTo(CLOSED);
        assertThat(FLOW.next("close", RUNNING)).isEqualTo(CLOSED);
    }

    /** 规则：当前状态做不了这个动作 → 1002（按钮是按旧状态显示的，状态已经被别人改了）；状态是 null 也一样 */
    @Test
    void next_notAllowed_throws1002() {
        assertThatThrownBy(() -> FLOW.next("audit", DRAFT))
                .isInstanceOf(BizException.class)
                .hasMessage("状态已变化，请刷新后再试")
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
        assertThatThrownBy(() -> FLOW.next("void", APPROVED))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
        assertThatThrownBy(() -> FLOW.next("submit", null))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
    }

    /**
     * 规则：去向不止一个的动作（取消结案按累计数回到哪一步），去哪个由调用方给，这里只查能不能；
     * 不给去向是程序写错了；给的去向不在表里也是；起点不对照样 1002
     */
    @Test
    void next_multiTarget_callerPicksTarget() {
        assertThat(FLOW.next("reopen", CLOSED, RUNNING)).isEqualTo(RUNNING);
        assertThatThrownBy(() -> FLOW.next("reopen", CLOSED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("有几个去向");
        assertThatThrownBy(() -> FLOW.next("reopen", CLOSED, DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能到 DRAFT");
        assertThatThrownBy(() -> FLOW.next("reopen", DONE, RUNNING))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
    }

    /** 规则：动作名不在表里（拼错了）不是 1002，是程序的错，第一次跑就要炸出来 */
    @Test
    void next_unknownAction_isProgrammingError() {
        assertThatThrownBy(() -> FLOW.next("sumbit", DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有动作 sumbit");
    }

    /** 规则：某个状态下能做的动作按表里的顺序给（详情接口的 actions、前端按钮的顺序）；到头的状态一个都没有 */
    @Test
    void actions_inTableOrder() {
        assertThat(FLOW.actions(PENDING)).containsExactly("withdraw", "reject", "audit", "void");
        assertThat(FLOW.actions(APPROVED)).containsExactly("unaudit", "start", "close");
        assertThat(FLOW.actions(VOIDED)).isEmpty();
        assertThat(FLOW.allows("start", APPROVED)).isTrue();
        assertThat(FLOW.allows("start", RUNNING)).isFalse();
    }

    /** 规则：一个动作写了两行是表写错了（后一行会悄悄盖掉前一行），建表时就报 */
    @Test
    void build_duplicateAction_throws() {
        StatusFlow.Builder<DocStatus> builder = StatusFlow.of(DocStatus.class).on("void", DRAFT).to(VOIDED);

        assertThatThrownBy(() -> builder.on("void", PENDING))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写了两行");
    }
}
