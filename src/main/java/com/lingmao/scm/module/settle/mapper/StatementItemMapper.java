package com.lingmao.scm.module.settle.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.settle.dto.StatementItemVO;
import com.lingmao.scm.module.settle.entity.StatementItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 对账单明细 */
@Mapper
public interface StatementItemMapper extends BaseMapper<StatementItem> {

    @Select("SELECT * FROM stl_statement_item WHERE statement_id = #{statementId} ORDER BY seq, id")
    List<StatementItem> selectByStatement(@Param("statementId") long statementId);

    /** 详情的明细：回货单带上生产单 id（链接用） */
    @Select("SELECT i.*, CASE WHEN i.source_type = 'PRODUCTION_RECEIPT' THEN r.prd_order_id END AS parent_id "
            + "FROM stl_statement_item i LEFT JOIN prd_receipt r ON i.source_type = 'PRODUCTION_RECEIPT' AND r.id = i.source_id "
            + "WHERE i.statement_id = #{statementId} ORDER BY i.seq, i.id")
    List<StatementItemVO> selectViews(@Param("statementId") long statementId);

    @Select("SELECT COALESCE(MAX(seq), 0) FROM stl_statement_item WHERE statement_id = #{statementId}")
    int maxSeq(@Param("statementId") long statementId);

    /** 作废：明细都放开，单据回到"待对账" */
    @Update("UPDATE stl_statement_item SET active = false WHERE statement_id = #{statementId}")
    int deactivate(@Param("statementId") long statementId);

    /** 扣款登记进了哪张没作废的对账单 */
    @Select("SELECT statement_id FROM stl_statement_item WHERE source_type = 'DEDUCTION' AND source_id = #{deductionId} AND active")
    Long statementOfDeduction(@Param("deductionId") long deductionId);
}
