package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设计师编号在真库上（RFC 005 §3.3、RFC 003 §4.3）：已删除的账号的编号也不回收；锁了以后条件更新改不动；
 * 绕过查重同时给两个人，唯一索引兜底。编号只有 99 个，测试挑一个库里没用过的
 */
@IntegrationTest
class UserDesignerNoIT {

    /** 操作人：不是被改的那个人就行（不能停用、删除自己的规则不碰） */
    private static final long OPERATOR = -1L;

    @Autowired
    private UserService userService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 规则：删掉的人的编号别人也不能用（款号里还有它） */
    @Test
    void deletedUsersNumber_notReused() {
        String no = freeNo();
        long id = userService.create(createReq(no));
        userService.delete(id, OPERATOR);

        assertThatThrownBy(() -> userService.create(createReq(no)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已删除的账号，编号不回收");
    }

    /** 规则：编过款号（designer_no_locked）以后改、清空都被拒，编号不变；没锁的能改 */
    @Test
    void locked_cannotChangeOrClear() {
        String no = freeNo();
        long id = userService.create(createReq(no));
        String other = freeNo();
        userService.update(id, updateReq(other), OPERATOR);
        assertThat(designerNo(id)).isEqualTo(other);

        jdbcTemplate.update("UPDATE sys_user SET designer_no_locked = true WHERE id = ?", id);
        assertThatThrownBy(() -> userService.update(id, updateReq(null), OPERATOR))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能清空");
        assertThat(designerNo(id)).isEqualTo(other);

        UserUpdateReq sameNo = updateReq(other);
        sameNo.setDept("设计部");
        userService.update(id, sameNo, OPERATOR);                      // 编号没变，别的照样能改
        assertThat(jdbcTemplate.queryForObject("SELECT dept FROM sys_user WHERE id = ?", String.class, id)).isEqualTo("设计部");
    }

    /** 规则：查重和插入之间别人抢先用了这个编号，唯一索引 uk_sys_user_designer_no 拦住 */
    @Test
    void uniqueIndex_backsUpTheCheck() {
        String no = freeNo();
        userService.create(createReq(no));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO sys_user (username, password, nickname, designer_no) VALUES (?, 'x', '抢号的', ?)",
                "it" + System.nanoTime(), no))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private String designerNo(long id) {
        return jdbcTemplate.queryForObject("SELECT designer_no FROM sys_user WHERE id = ?", String.class, id);
    }

    /** 01-99 里库里还没用过的一个 */
    private String freeNo() {
        for (int i = 0; i < 500; i++) {
            String no = String.format("%02d", ThreadLocalRandom.current().nextInt(1, 100));
            Integer used = jdbcTemplate.queryForObject("SELECT count(*) FROM sys_user WHERE designer_no = ?", Integer.class, no);
            if (used == 0) {
                return no;
            }
        }
        throw new IllegalStateException("01-99 都用完了：重建 IT 库再跑");
    }

    private static UserCreateReq createReq(String designerNo) {
        UserCreateReq req = new UserCreateReq();
        req.setUsername("it" + System.nanoTime());
        req.setPassword("Abc12345");
        req.setNickname("设计师" + designerNo);
        req.setDesignerNo(designerNo);
        return req;
    }

    private static UserUpdateReq updateReq(String designerNo) {
        UserUpdateReq req = new UserUpdateReq();
        req.setNickname("设计师");
        req.setStatus(1);
        req.setDesignerNo(designerNo);
        return req;
    }
}
