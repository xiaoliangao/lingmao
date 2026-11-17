package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.production.entity.ProductionReceiptItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ProductionReceiptItemMapper extends BaseMapper<ProductionReceiptItem> {

    @Select("<script>SELECT * FROM prd_receipt_item WHERE receipt_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY receipt_id, seq, id</script>")
    List<ProductionReceiptItem> selectByReceipts(@Param("ids") Collection<Long> receiptIds);
}
