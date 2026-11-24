package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.entity.InvDoc;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvDocMapper extends BaseMapper<InvDoc> {
    /** 预检只读编号和主体；每页不保留完整单头及聚合明细。 */
    @org.apache.ibatis.annotations.Options(useCache = false, flushCache = org.apache.ibatis.annotations.Options.FlushCachePolicy.TRUE)
    IPage<InvDocRowVO> selectExportHeads(IPage<InvDocRowVO> page, @Param("q") InvDocQuery query, @Param("codes") List<String> codes,
                                  @Param("scope") DataScope scope);


    /** 列表（XML）：仓库名、往来名只读 JOIN；codes 是关键字里的几个单号（有空格时），没有是 null */
    IPage<InvDocRowVO> selectRowPage(IPage<InvDocRowVO> page, @Param("q") InvDocQuery query, @Param("codes") List<String> codes,
                                     @Param("scope") DataScope scope);


    /** 往来单位的名字（供应商、加工厂的简称）：只读 sup_supplier，inventory 不调 supplier（架构 §3.2 规则 4） */
    @Select("SELECT short_name FROM sup_supplier WHERE id = #{id}")
    String partnerName(@Param("id") long id);

    /** 改草稿、删除前锁住单头：和审核、反审核排队 */
    @Select("SELECT * FROM inv_doc WHERE id = #{id} FOR UPDATE")
    InvDoc lockById(@Param("id") long id);
}
