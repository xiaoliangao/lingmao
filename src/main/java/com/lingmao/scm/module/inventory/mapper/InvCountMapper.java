package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.inventory.dto.CountOpenWarehouseVO;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.entity.InvCount;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvCountMapper extends BaseMapper<InvCount> {

    /** 列表（XML）：明细条数、已填、盘盈、盘亏现算 */
    IPage<CountRowVO> selectRowPage(IPage<CountRowVO> page, @Param("q") CountQuery query, @Param("scope") DataScope scope);


    @Select("SELECT * FROM inv_count WHERE id = #{id} FOR UPDATE")
    InvCount lockById(@Param("id") long id);

    /** 有没审核的盘点单（草稿、待审核）的仓库：一个仓最多一张（uk_inv_count_open） */
    @Select("SELECT warehouse_id, id AS count_id, no AS count_no, status FROM inv_count "
            + "WHERE status IN ('DRAFT', 'PENDING') ORDER BY warehouse_id")
    List<CountOpenWarehouseVO> selectOpen();
}
