package com.lingmao.scm.module.supplier.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.supplier.entity.CertSummary;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.entity.SupplierGoodAtRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 供应商和它的两张关联表（分类、擅长品类）。关联表没有 id，不建实体的 BaseMapper，读写都写在这里
 */
@Mapper
public interface SupplierMapper extends BaseMapper<Supplier> {

    /**
     * 锁住这一行再读（SELECT … FOR UPDATE），持有到事务结束。申请准入、删除先锁：
     * 锁住以后再查资质、再改状态，和资质增删的 FOR SHARE 互斥（RFC 004 §2.2、§13）。
     * resultMap 用实体自动生成的那个，领猫附加属性（JSONB）才读得出来
     */
    @Select("SELECT * FROM sup_supplier WHERE id = #{id} FOR UPDATE")
    @ResultMap("mybatis-plus_Supplier")
    Supplier selectByIdForUpdate(@Param("id") long id);

    /**
     * 资质增删前锁住供应商行（共享锁）再看状态：和申请准入的 FOR UPDATE 互斥，
     * 申请准入时不会有人正在删营业执照（RFC 004 §2.2）。没有这一家返回 null
     */
    @Select("SELECT status FROM sup_supplier WHERE id = #{id} FOR SHARE")
    String selectStatusForShare(@Param("id") long id);

    // ---------- 分类 ----------

    @Select("SELECT category FROM sup_supplier_category WHERE supplier_id = #{id}")
    List<String> selectCategories(@Param("id") long id);

    @Select("""
            <script>
            SELECT supplier_id, category FROM sup_supplier_category WHERE supplier_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SupplierCategoryRow> selectCategoriesOf(@Param("ids") Collection<Long> ids);

    /** 导出专用：查询在数据库内截到剩余预算 + 1 行，普通列表/表单读取不受影响。 */
    @Select("""
            <script>
            SELECT c.supplier_id, c.category
            FROM sup_supplier_category c
            LEFT JOIN sys_dict_data d ON d.type_code = 'supplier_category' AND d.value = c.category AND d.deleted = false
            WHERE
            <choose>
                <when test="ids != null and ids.size() > 0">
                    c.supplier_id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>false</otherwise>
            </choose>
            ORDER BY c.supplier_id, d.sort NULLS LAST, d.id NULLS LAST, c.category
            LIMIT #{limit}
            </script>
            """)
    List<SupplierCategoryRow> selectExportCategoriesOf(@Param("ids") Collection<Long> ids, @Param("limit") int limit);

    @Insert("""
            <script>
            INSERT INTO sup_supplier_category (supplier_id, category) VALUES
            <foreach collection="rows" item="r" separator=",">(#{r.supplierId}, #{r.category})</foreach>
            </script>
            """)
    int insertCategories(@Param("rows") List<SupplierCategoryRow> rows);

    @Delete("DELETE FROM sup_supplier_category WHERE supplier_id = #{id}")
    int deleteCategories(@Param("id") long id);

    // ---------- 擅长品类 ----------

    /** 带品类名（JOIN bas_category 只取名字显示，架构 §3.2 第 4 条），按品类树的顺序 */
    @Select("""
            <script>
            SELECT g.supplier_id, g.category_id, c.name AS category_name
            FROM sup_supplier_good_at g JOIN bas_category c ON c.id = g.category_id
            WHERE g.supplier_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY c.level, c.sort, c.id
            </script>
            """)
    List<SupplierGoodAtRow> selectGoodAtOf(@Param("ids") Collection<Long> ids);

    /** 导出专用：同一父项内仍按品类树排序，SQL LIMIT 必须由服务端剩余预算给出。 */
    @Select("""
            <script>
            SELECT g.supplier_id, g.category_id, c.name AS category_name
            FROM sup_supplier_good_at g JOIN bas_category c ON c.id = g.category_id
            WHERE
            <choose>
                <when test="ids != null and ids.size() > 0">
                    g.supplier_id IN
                    <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
                </when>
                <otherwise>false</otherwise>
            </choose>
            ORDER BY g.supplier_id, c.level, c.sort, c.id
            LIMIT #{limit}
            </script>
            """)
    List<SupplierGoodAtRow> selectExportGoodAtOf(@Param("ids") Collection<Long> ids, @Param("limit") int limit);

    @Insert("""
            <script>
            INSERT INTO sup_supplier_good_at (supplier_id, category_id) VALUES
            <foreach collection="rows" item="r" separator=",">(#{r.supplierId}, #{r.categoryId})</foreach>
            </script>
            """)
    int insertGoodAt(@Param("rows") List<SupplierGoodAtRow> rows);

    @Delete("DELETE FROM sup_supplier_good_at WHERE supplier_id = #{id}")
    int deleteGoodAt(@Param("id") long id);

    // ---------- 列表用 ----------

    /** 一页供应商各自有几份证照、最早哪天到期：一页查一次，不每行查 */
    @Select("""
            <script>
            SELECT supplier_id, count(*) AS cert_count, min(valid_until) AS earliest_until
            FROM sup_supplier_cert WHERE supplier_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY supplier_id
            </script>
            """)
    List<CertSummary> selectCertSummaries(@Param("ids") Collection<Long> ids);

    /** 导入查重：这些编码里库里已经有的 */
    @Select("""
            <script>
            SELECT code FROM sup_supplier WHERE code IN
            <foreach collection="codes" item="c" open="(" separator="," close=")">#{c}</foreach>
            </script>
            """)
    List<String> selectExistingCodes(@Param("codes") Collection<String> codes);
}
