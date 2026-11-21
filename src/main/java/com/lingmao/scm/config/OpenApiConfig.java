package com.lingmao.scm.config;

import com.lingmao.scm.security.LoginUser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接口文档（springdoc：/swagger-ui.html、/v3/api-docs，发版时导出成 openapi.json）。以后助手按它认工具和参数（RFC 009 §10）：
 * 说明先取 @Operation、@Parameter，没写的取 Javadoc（therapi，见 pom）；列表的查询条件对象拆成一个个查询参数
 * （application.yaml 的 default-flat-param-object）。
 * <p>
 * 拆的时候两样不能拆：当前登录的人（@AuthenticationPrincipal LoginUser）不是请求参数，拆了多出 userId、jti 两个假参数；
 * 上传的文件（MultipartFile）是接口、没有字段，拆了就没了，上传、导入接口的 file 会从文档里消失。
 * 参数还是没有说明的，按名字补一句通用的（parameterFallbacks）：xxxId、xxxDateFrom 这种各处意思一样的不用每个 Query 写一遍，
 * 认不出的不编。意思因接口而异的（keyword 查哪些字段、status 的取值）写在 Query 字段的 Javadoc 或 @Parameter 上，那里写了就用那里的
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(LoginUser.class);
        SpringDocUtils.getConfig().addSimpleTypesForParameterObject(MultipartFile.class);
    }

    /** xxxId、xxxIds 的 xxx → 叫法 */
    private static final Map<String, String> NOUNS = Map.ofEntries(
            Map.entry("warehouse", "仓库"), Map.entry("style", "款式"), Map.entry("styleSku", "款式 SKU"),
            Map.entry("merch", "跟单员"), Map.entry("factory", "加工厂"), Map.entry("order", "需求订单"),
            Map.entry("user", "用户"), Map.entry("category", "分类"), Map.entry("supplier", "供应商"),
            Map.entry("defaultSupplier", "默认供应商"), Map.entry("sales", "业务员"), Map.entry("buyer", "采购员"),
            Map.entry("designer", "设计师"), Map.entry("sku", "SKU"), Map.entry("material", "物料"),
            Map.entry("materialSku", "物料 SKU"), Map.entry("partner", "往来单位"), Map.entry("company", "主体公司"),
            Map.entry("receipt", "回货单"));
    /** 路径里 /{id}/xxx/{xxxId} 的 xxx → 地址里这一条下面的哪一样；NOUNS 里有的也认 */
    private static final Map<String, String> PATH_NOUNS = Map.ofEntries(
            Map.entry("source", "来源单据"), Map.entry("notice", "提醒"), Map.entry("node", "进度节点"),
            Map.entry("issue", "异常"), Map.entry("item", "明细行"), Map.entry("color", "颜色"),
            Map.entry("change", "改动申请"), Map.entry("cert", "证照"), Map.entry("file", "文件"));
    /** xxxDateFrom、xxxDateTo 的 xxx → 哪个日期 */
    private static final Map<String, String> DATES = Map.of(
            "order", "下单日期", "due", "交期", "biz", "业务日期", "doc", "单据日期", "create", "创建日期",
            "created", "创建日期", "count", "盘点日期", "receipt", "回货日期");
    /** 名字本身就够说明的 */
    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("ids", "只要这几条（按 id；勾选后导出时用），不传按其他条件"),
            Map.entry("mine", "true：只看和我有关的（我建的或我负责的）"),
            Map.entry("auditForMe", "true：只看等我审核的"),
            Map.entry("keyword", "关键字：单号、编码、名称这类，模糊查"),
            Map.entry("status", "状态；不传是全部"),
            Map.entry("createBy", "创建人的用户 id"),
            Map.entry("brand", "品牌（字典 brand 的值）"), Map.entry("season", "季节（字典 season 的值）"),
            Map.entry("year", "年份"), Map.entry("wave", "波段"), Map.entry("productLine", "品类线"),
            Map.entry("materialType", "物料类型（字典 material_type 的值）"),
            Map.entry("materialTypes", "只要这几种物料类型（字典 material_type 的值）"),
            Map.entry("excludeMaterialTypes", "不要这几种物料类型（字典 material_type 的值）"),
            Map.entry("includeId", "不管别的条件，这一条也带上（编辑时回显已经选的）"),
            Map.entry("excludeId", "不要这一条（比如改的就是它自己）"));
    private static final Pattern ID = Pattern.compile("^([a-z][A-Za-z]*?)Ids?$");
    private static final Pattern DATE = Pattern.compile("^([a-z]+?)Date(From|To)$");

    /** 没有说明的参数，按名字补一句通用的；有说明（注解、Javadoc）的不动。路径里的 {id} 是地址里那一条 */
    @Bean
    public OpenApiCustomizer parameterFallbacks() {
        return openApi -> parameters(openApi).forEach(parameter -> {
            if (StringUtils.hasText(parameter.getDescription())) {
                return;
            }
            if ("query".equals(parameter.getIn())) {
                parameter.setDescription(fallback(parameter.getName()));
            } else if ("path".equals(parameter.getIn())) {
                parameter.setDescription(pathFallback(parameter.getName()));
            }
        });
    }

    /** 路径里的 xxxId：这一条下面的哪个 xxx；认不出的是 null */
    static String pathFallback(String name) {
        if ("id".equals(name)) {
            return "地址里这一条的 id";
        }
        Matcher id = ID.matcher(name == null ? "" : name);
        if (!id.matches()) {
            return null;
        }
        String noun = PATH_NOUNS.getOrDefault(id.group(1), NOUNS.get(id.group(1)));
        return noun == null ? null : noun + "的 id";
    }

    private static java.util.stream.Stream<Parameter> parameters(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return java.util.stream.Stream.empty();
        }
        return openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .filter(operation -> operation.getParameters() != null)
                .flatMap(operation -> operation.getParameters().stream())
                .filter(Objects::nonNull);
    }

    /** 参数名 → 通用说明；认不出的是 null */
    static String fallback(String name) {
        if (name == null) {
            return null;
        }
        String known = NAMES.get(name);
        if (known != null) {
            return known;
        }
        Matcher date = DATE.matcher(name);
        if (date.matches() && DATES.containsKey(date.group(1))) {
            return DATES.get(date.group(1)) + ("From".equals(date.group(2)) ? "从这天起（含）" : "到这天为止（含）");
        }
        Matcher id = ID.matcher(name);
        if (id.matches() && NOUNS.containsKey(id.group(1))) {
            return (name.endsWith("Ids") ? "按这几个" : "按") + NOUNS.get(id.group(1)) + " id 筛";
        }
        return null;
    }
}
