package com.lingmao.scm.module.settle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.settle.dto.DeductionQuery;
import com.lingmao.scm.module.settle.dto.DeductionRowVO;
import com.lingmao.scm.module.settle.entity.Deduction;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 扣款登记 */
@Mapper
public interface DeductionMapper extends BaseMapper<Deduction> {

    /** 列表（XML）：带进了哪张没作废的对账单 */
    IPage<DeductionRowVO> selectRowPage(IPage<DeductionRowVO> page, @Param("q") DeductionQuery query, @Param("scope") DataScope scope);

    /** 改、删前锁住：和生成对账单排队 */
    @Select("SELECT * FROM stl_deduction WHERE id = #{id} FOR UPDATE")
    Deduction lockById(@Param("id") long id);
}
