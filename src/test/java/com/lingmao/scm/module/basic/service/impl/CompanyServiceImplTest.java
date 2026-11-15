package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.CompanySaveReq;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.mapper.CompanyMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompanyServiceImplTest {

    @Mock
    private CompanyMapper companyMapper;

    @InjectMocks
    private CompanyServiceImpl companyService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Company.class);
    }

    /** 规则（RFC 003 §3.1）：最后一个启用的主体公司不能停用，不发 update */
    @Test
    void disable_lastEnabled_rejected() {
        when(companyMapper.selectById(1L)).thenReturn(company(1L, "LM"));
        when(companyMapper.lockEnabledIds()).thenReturn(List.of(1L));

        assertThatThrownBy(() -> companyService.disable(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("最后一个启用的主体公司，不能停用");
        verify(companyMapper, never()).update(any(), any());
    }

    /** 规则：还有别的启用的，就能停用 */
    @Test
    void disable_anotherEnabled_updatesStatus() {
        when(companyMapper.selectById(1L)).thenReturn(company(1L, "LM"));
        when(companyMapper.lockEnabledIds()).thenReturn(List.of(1L, 2L));
        when(companyMapper.update(any(), any())).thenReturn(1);

        companyService.disable(1L);

        verify(companyMapper).update(any(), any());
    }

    /** 规则：最后一个启用的也不能删 */
    @Test
    void delete_lastEnabled_rejected() {
        when(companyMapper.selectById(1L)).thenReturn(company(1L, "LM"));
        when(companyMapper.lockEnabledIds()).thenReturn(List.of(1L));

        assertThatThrownBy(() -> companyService.delete(1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能删除");
        verify(companyMapper, never()).deleteById(any(Long.class));
    }

    /** 规则：编码存大写、去空格；建好不能改 */
    @Test
    void create_codeUppercased_updateCodeChangeRejected() {
        when(companyMapper.exists(any())).thenReturn(false);
        CompanySaveReq req = req(" lm01 ");

        companyService.create(req);

        ArgumentCaptor<Company> saved = ArgumentCaptor.captor();
        verify(companyMapper).insert(saved.capture());
        assertThat(saved.getValue().getCode()).isEqualTo("LM01");

        when(companyMapper.selectById(1L)).thenReturn(company(1L, "LM01"));
        assertThatThrownBy(() -> companyService.update(1L, req("LM02")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("编码建好就不能改");
        verify(companyMapper, never()).update(any(), any());
    }

    private static Company company(Long id, String code) {
        Company c = new Company();
        c.setId(id);
        c.setCode(code);
        c.setName("公司" + code);
        c.setStatus(1);
        return c;
    }

    private static CompanySaveReq req(String code) {
        CompanySaveReq req = new CompanySaveReq();
        req.setCode(code);
        req.setName("某某服饰有限公司");
        return req;
    }
}
