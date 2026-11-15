package com.lingmao.scm.common.exception;

import com.jayway.jsonpath.JsonPath;
import com.lingmao.scm.common.log.TraceIdFilter;
import com.lingmao.scm.common.mybatis.TableComments;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.result.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 全局异常处理的接口测试：Spring MVC 自己抛的各种异常、业务异常、程序错误，各自变成什么样的 R（RFC 001 §5.2）。
 * 每种情况由下面测试专用的 DemoController 造出来，不依赖哪个业务模块。
 * MockMvc 自己建：只挂错误编号过滤器，不挂 Spring Security。要测响应头和日志里的编号，又不想处理登录。
 */
@WebMvcTest(GlobalExceptionHandlerTest.DemoController.class)
@Import(GlobalExceptionHandlerTest.DemoController.class)
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    /** 外键冲突时查引用方的中文表名；这里不连数据库，用假的 */
    @MockitoBean
    private TableComments tableComments;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
    }

    /** bug 003：请求体根本不是 JSON，返回 400，不是 500 */
    @Test
    void notJson_returns400() throws Exception {
        mockMvc.perform(post("/demo/items").contentType(MediaType.APPLICATION_JSON).content("名称=季节"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求体格式不对"));
    }

    /** bug 003：某个字段的类型不对，返回 400，并指出是哪个字段（嵌套在明细里的也认得出） */
    @Test
    void wrongFieldType_returns400WithFieldPath() throws Exception {
        mockMvc.perform(post("/demo/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "季节", "lines": [{"qty": "十件"}]}
                                """))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求体格式不对：字段 lines[0].qty"));
    }

    /** bug 004：只用注解上写的 message，前面不再拼字段名 */
    @Test
    void validationFailed_usesMessageOnly() throws Exception {
        mockMvc.perform(get("/demo/items").param("size", "1000"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("size 最多 500"));
    }

    /** 几条校验都没过：每条消息都在，用"；"隔开（顺序不固定） */
    @Test
    void severalValidationErrors_joined() throws Exception {
        mockMvc.perform(get("/demo/items").param("page", "0").param("size", "1000"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(allOf(
                        containsString("page 从 1 开始"), containsString("；"), containsString("size 最多 500"))));
    }

    /** 校验注解直接写在 Controller 方法参数上（Spring 走的是另一种异常），消息取法一样 */
    @Test
    void methodParamValidationFailed_usesMessageOnly() throws Exception {
        mockMvc.perform(get("/demo/latest").param("n", "0"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("条数至少 1"));
    }

    /** 查询参数的类型不对：?page=abc */
    @Test
    void queryParamTypeWrong_returns400() throws Exception {
        mockMvc.perform(get("/demo/items").param("page", "abc"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("参数 page 格式不对"));
    }

    /** 路径参数的类型不对：/demo/items/abc */
    @Test
    void pathVariableTypeWrong_returns400() throws Exception {
        mockMvc.perform(get("/demo/items/abc"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("参数 id 格式不对"));
    }

    /** 缺了必填的查询参数 */
    @Test
    void missingRequestParam_returns400() throws Exception {
        mockMvc.perform(get("/demo/search"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("缺少参数 keyword"));
    }

    /** Content-Type 不对：收 JSON 的接口传了纯文本 */
    @Test
    void wrongContentType_returns400() throws Exception {
        mockMvc.perform(post("/demo/items").contentType(MediaType.TEXT_PLAIN).content("季节"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value(containsString("不支持的 Content-Type: text/plain")));
    }

    /** bug 006：方法不对返回 405，不是 500 */
    @Test
    void wrongMethod_returns405() throws Exception {
        mockMvc.perform(delete("/demo/items/1"))
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.msg").value("不支持 DELETE 请求"));
    }

    /** 唯一索引冲突（查重之后别人刚好建了同样的编码）：返回 1000 提示刷新，日志里记下约束名 */
    @Test
    void duplicateKey_returns1000AndLogsConstraint(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/demo/duplicate"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.msg").value("数据重复，可能别人刚建了同样的编码，请刷新后再试"));

        assertThat(output.getOut()).contains("唯一约束冲突: uk_sys_dict_type_code");
    }

    /** RFC 001 §18：上传请求本身坏了（这里是根本不是 multipart），返回 400，不是 500 */
    @Test
    void brokenMultipart_returns400() throws Exception {
        mockMvc.perform(post("/demo/upload").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("上传失败：请求不完整或格式不对，请重新上传"));
    }

    /**
     * 用户 11-15 定：删除被外键拦住，返回 1005，用引用方的表注释说清是被什么引用了。
     * 引用方从 detail（is still referenced from table "pur_order"）里取；SQL 原文、英文表名不给前端
     */
    @Test
    void foreignKeyOnDelete_returns1005WithTableComment() throws Exception {
        when(tableComments.of("pur_order")).thenReturn("采购单");

        mockMvc.perform(get("/demo/fk-delete"))
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.msg").value("已被采购单引用，不能删除，只能停用"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    /** 连接参数关了 detail 时，从第一行的 on table "pur_order" 取引用方 */
    @Test
    void foreignKeyOnDelete_withoutDetail_usesFirstLine() throws Exception {
        when(tableComments.of("pur_order")).thenReturn("采购单");

        mockMvc.perform(get("/demo/fk-delete-no-detail"))
                .andExpect(jsonPath("$.msg").value("已被采购单引用，不能删除，只能停用"));
    }

    /** 引用方的表没写注释（或者查不到）：说"其他数据"，不露英文表名 */
    @Test
    void foreignKeyOnDelete_noComment_saysOtherData() throws Exception {
        when(tableComments.of("pur_order")).thenReturn(null);

        mockMvc.perform(get("/demo/fk-delete"))
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.msg").value("已被其他数据引用，不能删除，只能停用"));
    }

    /** 报错认不出（比如数据库换成了中文报错）：按删除处理，说"其他数据"，不去查表注释 */
    @Test
    void foreignKeyUnrecognized_saysOtherData() throws Exception {
        mockMvc.perform(get("/demo/fk-unknown"))
                .andExpect(jsonPath("$.code").value(1005))
                .andExpect(jsonPath("$.msg").value("已被其他数据引用，不能删除，只能停用"));

        verify(tableComments, never()).of(anyString());
    }

    /** 插入时引用的数据不存在（页面开着时别人把它删了）：不是"只能停用"，返回 1000 让用户刷新 */
    @Test
    void foreignKeyOnInsert_returns1000() throws Exception {
        mockMvc.perform(get("/demo/fk-insert"))
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.msg").value("关联的数据不存在，可能刚被别人删除了，请刷新后再试"));
    }

    /** 别的约束（非空、检查约束）说明少了一处校验，是程序错误：500 */
    @Test
    void notNullViolation_returns500() throws Exception {
        mockMvc.perform(get("/demo/not-null"))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(containsString("服务器出错了")));
    }

    /** 业务异常带的数据原样放进 data：导入校验没通过时的错误清单 */
    @Test
    void bizExceptionWithData_returnsDataToFrontend() throws Exception {
        mockMvc.perform(get("/demo/import"))
                .andExpect(jsonPath("$.code").value(1003))
                .andExpect(jsonPath("$.msg").value("导入校验没通过"))
                .andExpect(jsonPath("$.data[0].row").value(2))
                .andExpect(jsonPath("$.data[0].column").value("编码"))
                .andExpect(jsonPath("$.data[0].message").value("编码不能为空"));
    }

    /** 每个响应都带 X-Trace-Id，8 位十六进制，成功的也带 */
    @Test
    void everyResponse_hasTraceIdHeader() throws Exception {
        mockMvc.perform(get("/demo/items/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string(TraceIdFilter.HEADER, matchesPattern("[0-9a-f]{8}")));
    }

    /** 程序错误：返回 500，提示里的错误编号就是响应头里的那个；日志里那一行也带着这个编号，grep 得到 */
    @Test
    void unexpectedError_returns500WithTraceId(CapturedOutput output) throws Exception {
        MvcResult result = mockMvc.perform(get("/demo/boom"))
                .andExpect(jsonPath("$.code").value(500))
                .andReturn();

        String traceId = result.getResponse().getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{8}");
        String msg = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.msg");
        assertThat(msg).isEqualTo("服务器出错了，错误编号 " + traceId);
        assertThat(output.getOut()).containsPattern("\\[" + traceId + "\\] .*未处理异常: GET /demo/boom");
    }

    /** 测试专用：每个接口造一种出错的情况 */
    @RestController
    @RequestMapping("/demo")
    static class DemoController {

        @GetMapping("/items")
        public R<Void> page(@Valid PageQuery query) {
            return R.ok();
        }

        @GetMapping("/items/{id}")
        public R<Long> get(@PathVariable("id") Long id) {
            return R.ok(id);
        }

        @PostMapping("/items")
        public R<Void> create(@Valid @RequestBody DemoReq req) {
            return R.ok();
        }

        @GetMapping("/search")
        public R<Void> search(@RequestParam("keyword") String keyword) {
            return R.ok();
        }

        @GetMapping("/latest")
        public R<Void> latest(@RequestParam("n") @Min(value = 1, message = "条数至少 1") int n) {
            return R.ok();
        }

        @GetMapping("/duplicate")
        public R<Void> duplicate() {
            throw new DuplicateKeyException("插入字典类型失败", new SQLException(
                    "ERROR: duplicate key value violates unique constraint \"uk_sys_dict_type_code\"", "23505"));
        }

        @PostMapping("/upload")
        public R<Void> upload(@RequestParam("file") MultipartFile file) {
            return R.ok();
        }

        /** PgJDBC 的消息：第一行是报错，下一行是 detail */
        @GetMapping("/fk-delete")
        public R<Void> fkDelete() {
            throw new DataIntegrityViolationException("删除供应商失败", new SQLException(
                    "ERROR: update or delete on table \"sup_supplier\" violates foreign key constraint "
                            + "\"fk_pur_order_supplier\" on table \"pur_order\"\n"
                            + "  Detail: Key (id)=(1) is still referenced from table \"pur_order\".", "23503"));
        }

        @GetMapping("/fk-delete-no-detail")
        public R<Void> fkDeleteNoDetail() {
            throw new DataIntegrityViolationException("删除供应商失败", new SQLException(
                    "ERROR: update or delete on table \"sup_supplier\" violates foreign key constraint "
                            + "\"fk_pur_order_supplier\" on table \"pur_order\"", "23503"));
        }

        @GetMapping("/fk-unknown")
        public R<Void> fkUnknown() {
            throw new DataIntegrityViolationException("删除供应商失败", new SQLException(
                    "错误: 在 \"sup_supplier\" 上的更新或删除操作违反了在 \"pur_order\" 上的外键约束", "23503"));
        }

        @GetMapping("/fk-insert")
        public R<Void> fkInsert() {
            throw new DataIntegrityViolationException("新增采购单失败", new SQLException(
                    "ERROR: insert or update on table \"pur_order\" violates foreign key constraint "
                            + "\"fk_pur_order_supplier\"\n"
                            + "  Detail: Key (supplier_id)=(9) is not present in table \"sup_supplier\".", "23503"));
        }

        @GetMapping("/not-null")
        public R<Void> notNull() {
            throw new DataIntegrityViolationException("新增失败", new SQLException(
                    "ERROR: null value in column \"name\" of relation \"sup_supplier\" violates not-null constraint", "23502"));
        }

        @GetMapping("/import")
        public R<Void> importFailed() {
            throw new BizException(ErrorCodes.IMPORT_INVALID, "导入校验没通过",
                    List.of(Map.of("row", 2, "column", "编码", "message", "编码不能为空")));
        }

        @GetMapping("/boom")
        public R<Void> boom() {
            throw new IllegalStateException("模拟的程序错误");
        }
    }

    @Data
    static class DemoReq {

        @NotBlank(message = "名称不能为空")
        private String name;

        private List<DemoLine> lines;
    }

    @Data
    static class DemoLine {
        private BigDecimal qty;
    }
}
