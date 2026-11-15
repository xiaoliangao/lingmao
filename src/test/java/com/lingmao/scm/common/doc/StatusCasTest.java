package com.lingmao.scm.common.doc;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 比较并交换改状态（架构 §4.2）：条件长什么样、0 行怎么报。两个线程同时改只有一个成功，在 StatusCasIT 里用真库测 */
class StatusCasTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Doc.class);
    }

    /** 规则：SET 状态 = 新状态，条件是 id 和查出来时的旧状态都对得上 */
    @Test
    void of_setsNewStatus_whereIdAndOldStatus() {
        LambdaUpdateWrapper<Doc> cas = StatusCas.of(Doc::getId, 7L, Doc::getStatus, DocStatus.PENDING, DocStatus.APPROVED);

        assertThat(cas.getSqlSet()).isEqualTo("status=#{ew.paramNameValuePairs.MPGENVAL1}");
        assertThat(cas.getSqlSegment())
                .isEqualTo("(id = #{ew.paramNameValuePairs.MPGENVAL2} AND status = #{ew.paramNameValuePairs.MPGENVAL3})");
        assertThat(cas.getParamNameValuePairs())
                .containsEntry("MPGENVAL1", DocStatus.APPROVED)
                .containsEntry("MPGENVAL2", 7L)
                .containsEntry("MPGENVAL3", DocStatus.PENDING);
    }

    /** 规则：改了 0 行（状态被别人改了、或者删了）→ 1002；改到了就正常返回，用的是传进来的空实体（审计列才会自动填） */
    @Test
    @SuppressWarnings("unchecked")
    void update_zeroRows_throws1002() {
        BaseMapper<Doc> mapper = mock(BaseMapper.class);
        Doc empty = new Doc();
        LambdaUpdateWrapper<Doc> cas = StatusCas.of(Doc::getId, 7L, Doc::getStatus, DocStatus.PENDING, DocStatus.APPROVED);
        when(mapper.update(same(empty), same(cas))).thenReturn(0, 1);

        assertThatThrownBy(() -> StatusCas.update(mapper, empty, cas))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCodes.STATE_CHANGED);
        StatusCas.update(mapper, empty, cas);
    }

    /** 规则：传 null 当实体是写错了（修改人、修改时间不会填），直接拒绝，不发 SQL；没有 id 也一样（会改掉整张表里这个状态的行） */
    @Test
    @SuppressWarnings("unchecked")
    void nullEntityOrId_rejected() {
        BaseMapper<Doc> mapper = mock(BaseMapper.class);

        assertThatThrownBy(() -> StatusCas.update(mapper, null,
                StatusCas.of(Doc::getId, 7L, Doc::getStatus, DocStatus.PENDING, DocStatus.APPROVED)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StatusCas.of(Doc::getId, null, Doc::getStatus, DocStatus.PENDING, DocStatus.APPROVED))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).update(any(), any());
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @TableName("biz_cas_doc")
    static class Doc extends AuditEntity {
        @TableField(updateStrategy = FieldStrategy.NEVER)
        private DocStatus status;
        private String remark;
    }
}
