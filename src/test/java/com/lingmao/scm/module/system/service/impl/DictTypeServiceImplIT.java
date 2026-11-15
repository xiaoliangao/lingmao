package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.module.system.dto.DictTypeSaveReq;
import com.lingmao.scm.module.system.entity.DictType;
import com.lingmao.scm.module.system.mapper.DictTypeMapper;
import com.lingmao.scm.module.system.service.DictTypeService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字典类型在真库上：bug 001（删了建不回来）、002（修改时间不变）、005（备注清不掉）。
 * 第一阶段只能用 Mockito 看"调了哪个方法"，这里看库里最后存的是什么
 */
@IntegrationTest
class DictTypeServiceImplIT {

    /** 审计人没有外键，随便两个 id 就行 */
    private static final long CREATOR = 900001L;
    private static final long EDITOR = 900002L;

    @Autowired
    private DictTypeService dictTypeService;

    @Autowired
    private DictTypeMapper dictTypeMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** bug 001：删了的类型（逻辑删除，行还在）同名能建回来，V8 把编码改成了只在没删除的行里唯一 */
    @Test
    void deleted_sameCodeCanBeCreatedAgain() {
        String code = randomCode();
        Long first = dictTypeService.create(req(code, null));
        dictTypeService.delete(first);

        Long second = dictTypeService.create(req(code, null));

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sys_dict_type WHERE code = ?", Integer.class, code))
                .isEqualTo(2);
    }

    /**
     * bug 002、005：修改以后 update_time、update_by 变成这一次的（创建人、创建时间不动）；备注传 null 库里就是 NULL。
     * 先把修改时间拨回到年初、修改人清空，模拟"很久以前别人建的"：修改是先查出实体再写回去，旧值不是 null，
     * 以前的 strictUpdateFill 看到有值就不填，修改时间就一直是旧的
     */
    @Test
    void update_refreshesAuditColumnsAndClearsNull() {
        String code = randomCode();
        loginAs(CREATOR);
        Long id = dictTypeService.create(req(code, "旧备注"));
        jdbcTemplate.update("UPDATE sys_dict_type SET update_time = '2026-01-01 00:00:00+08', update_by = NULL WHERE id = ?", id);

        loginAs(EDITOR);
        dictTypeService.update(id, req(code, null));

        DictType saved = dictTypeMapper.selectById(id);
        assertThat(saved.getUpdateTime()).isAfter(OffsetDateTime.parse("2026-01-02T00:00:00+08:00"));
        assertThat(saved.getUpdateBy()).isEqualTo(EDITOR);
        assertThat(saved.getCreateBy()).isEqualTo(CREATOR);
        assertThat(saved.getRemark()).isNull();
    }

    /** 整体覆盖只在保存时用；改状态这种条件更新还是只写 SET 的那几列，不会把备注一起清掉（RFC 001 §4.2） */
    @Test
    void statusAction_keepsOtherColumns() {
        Long id = dictTypeService.create(req(randomCode(), "留着"));

        dictTypeMapper.update(new DictType(), new LambdaUpdateWrapper<DictType>()
                .set(DictType::getStatus, 0)
                .eq(DictType::getId, id));

        DictType saved = dictTypeMapper.selectById(id);
        assertThat(saved.getStatus()).isZero();
        assertThat(saved.getRemark()).isEqualTo("留着");
    }

    private void loginAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "it"), null, List.of()));
    }

    private static DictTypeSaveReq req(String code, String remark) {
        DictTypeSaveReq req = new DictTypeSaveReq();
        req.setCode(code);
        req.setName("测试类型");
        req.setRemark(remark);
        return req;
    }

    private static String randomCode() {
        return "it_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
