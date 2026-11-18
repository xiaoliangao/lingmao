package com.lingmao.scm.module.style.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.basic.dto.TreeNode;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.service.CategoryService;
import com.lingmao.scm.module.basic.service.ColorService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.style.dto.StyleSaveReq;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleColor;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;
import com.lingmao.scm.module.style.mapper.StyleColorMapper;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.style.mapper.StyleSkuMapper;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * 款式几个单元测试共用的：一套 mock、假数据（名字都是编的）、MyBatis-Plus 的表信息、登录的人。
 * 测试里的"今天"是 2026-11-08（RFC 005 §12），款号的日期段是 261108
 */
abstract class StyleTestBase {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    static final long DESIGNER_07 = 21L;
    static final long DESIGNER_12 = 22L;
    static final long DESIGNER_NONE = 23L;
    static final long SIZE_GROUP = 7L;
    static final long OTHER_SIZE_GROUP = 8L;
    /** 品类：测试女装 › 测试上衣 › 测试衬衫（叶子），测试上衣不是叶子 */
    static final long CAT_TOP = 2L;
    static final long CAT_SHIRT = 3L;

    static final Color KQ = color(11L, "KQ", "卡其");
    static final Color ZQ = color(12L, "ZQ", "藏青");
    static final Color BK = color(13L, "BK", "黑");
    static final Color JH = color(14L, "JH", "酒红");
    static final List<String> SIZES = List.of("S", "M", "L", "XL", "XXL");

    @Mock
    StyleMapper styleMapper;
    @Mock
    StyleColorMapper styleColorMapper;
    @Mock
    StyleSkuMapper styleSkuMapper;
    @Mock
    SerialNoGenerator serialNoGenerator;
    @Mock
    UserService userService;
    @Mock
    DictDataService dictDataService;
    @Mock
    CategoryService categoryService;
    @Mock
    ColorService colorService;
    @Mock
    SizeGroupService sizeGroupService;
    @Mock
    MaterialService materialService;
    @Mock
    SupplierService supplierService;
    @Mock
    AttachmentService attachmentService;
    @Mock
    DocLogService docLogService;

    StyleCodes codes;
    StyleDicts dicts;
    StyleCategories categories;
    SkuGenerator skuGenerator;
    StyleExcel styleExcel;
    StyleServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> c : List.of(Style.class, StyleColor.class, StyleSku.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    @BeforeEach
    void setUpBase() {
        build(false);
        lenient().when(dictDataService.listByType(anyString())).thenReturn(List.of());
        lenient().when(dictDataService.listByType("style_class")).thenReturn(List.of(new DictOption("服饰", "FS", 1)));
        lenient().when(dictDataService.listByType("brand")).thenReturn(List.of(new DictOption("测试品牌", "TB", 1)));
        lenient().when(dictDataService.listByType("season")).thenReturn(List.of(
                new DictOption("春", "SPRING", 1), new DictOption("夏", "SUMMER", 1)));
        lenient().when(dictDataService.listByType("style_source")).thenReturn(List.of(
                new DictOption("自主开发", "OWN", 1), new DictOption("客户来样", "CUSTOMER_SAMPLE", 1)));

        TreeNode women = node(1L, null, "测试女装");
        TreeNode top = node(CAT_TOP, 1L, "测试上衣");
        TreeNode shirt = node(CAT_SHIRT, CAT_TOP, "测试衬衫");
        women.getChildren().add(top);
        top.getChildren().add(shirt);
        lenient().when(categoryService.tree()).thenReturn(List.of(women));

        lenient().when(sizeGroupService.getById(SIZE_GROUP)).thenReturn(new SizeGroupVO(SIZE_GROUP, "测试字母码", "M", 1, SIZES));
        lenient().when(sizeGroupService.getById(OTHER_SIZE_GROUP))
                .thenReturn(new SizeGroupVO(OTHER_SIZE_GROUP, "测试数字码", "160", 1, List.of("155", "160", "165")));

        Map<Long, Color> allColors = List.of(KQ, ZQ, BK, JH).stream().collect(Collectors.toMap(Color::getId, Function.identity()));
        lenient().when(colorService.byIds(any())).thenAnswer(inv -> inv.<Collection<Long>>getArgument(0).stream()
                .filter(allColors::containsKey).collect(Collectors.toMap(Function.identity(), allColors::get, (a, b) -> a)));
        lenient().when(colorService.getById(anyLong())).thenAnswer(inv -> allColors.get(inv.<Long>getArgument(0)));

        lenient().when(userService.getById(DESIGNER_07)).thenReturn(designer(DESIGNER_07, "甲某", "07", false));
        lenient().when(userService.getById(DESIGNER_12)).thenReturn(designer(DESIGNER_12, "乙某", "12", true));
        lenient().when(userService.getById(DESIGNER_NONE)).thenReturn(designer(DESIGNER_NONE, "丙某", null, false));

        AtomicLong ids = new AtomicLong(100);
        lenient().doAnswer(inv -> {
            inv.<Style>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(styleMapper).insert(any(Style.class));
        lenient().when(styleSkuMapper.insertMissing(any(), any(), any())).thenAnswer(inv -> inv.<List<?>>getArgument(0).size());
        loginAs(1L);
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 重新装一遍：trade 是代工开关 */
    void build(boolean trade) {
        TradeFeature tradeFeature = new TradeFeature(trade);
        codes = new StyleCodes(styleMapper, serialNoGenerator, userService, CLOCK);
        dicts = new StyleDicts(dictDataService);
        categories = new StyleCategories(categoryService);
        skuGenerator = new SkuGenerator(styleSkuMapper, CLOCK);
        styleExcel = new StyleExcel(styleMapper, styleColorMapper, styleSkuMapper, dicts, categories, skuGenerator,
                categoryService, colorService, sizeGroupService, materialService, userService, dictDataService, tradeFeature,
                docLogService, com.lingmao.scm.DirectFileImports.INSTANCE);
        service = new StyleServiceImpl(styleMapper, styleColorMapper, styleSkuMapper, codes, dicts, categories, skuGenerator,
                styleExcel, categoryService, colorService, sizeGroupService, materialService, supplierService, userService,
                tradeFeature, attachmentService, docLogService);
    }

    /** 当前登录的是 userId，带这几个权限码（不带前缀 style:style:，这里补） */
    static void loginAs(long userId, String... perms) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(perms)
                .map(p -> new SimpleGrantedAuthority("style:style:" + p)).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(userId, "jti-" + userId), null, authorities));
    }

    /** 一个填好了必填项的保存请求 */
    static <T extends StyleSaveReq> T fillReq(T req) {
        req.setName("测试风衣");
        req.setStyleClass("FS");
        req.setBrand("TB");
        req.setCategoryId(CAT_SHIRT);
        req.setYear(2027);
        req.setSeason("SPRING");
        req.setSizeGroupId(SIZE_GROUP);
        req.setDesignerId(DESIGNER_07);
        return req;
    }

    static StyleCreateReq autoReq(Long... colorIds) {
        StyleCreateReq req = fillReq(new StyleCreateReq());
        req.setCodeMode(StyleCreateReq.CodeMode.AUTO);
        req.setColorIds(List.of(colorIds));
        return req;
    }

    static Style style(long id, String code, StyleStatus status) {
        Style s = new Style();
        s.setId(id);
        s.setCode(code);
        s.setName("测试风衣");
        s.setStyleClass("FS");
        s.setBrand("TB");
        s.setCategoryId(CAT_SHIRT);
        s.setYear(2027);
        s.setSeason("SPRING");
        s.setSource("OWN");
        s.setSizeGroupId(SIZE_GROUP);
        s.setDesignerId(DESIGNER_07);
        s.setStatus(status);
        s.setTechPackStatus(TechPackStatus.NONE);
        return s;
    }

    static StyleColor styleColor(long styleId, Color color, int seq) {
        StyleColor sc = new StyleColor();
        sc.setId(1000L + color.getId());
        sc.setStyleId(styleId);
        sc.setColorId(color.getId());
        sc.setSeq(seq);
        sc.setStatus(1);
        return sc;
    }

    static StyleSku sku(long styleId, String styleCode, Color color, String size) {
        StyleSku k = new StyleSku();
        k.setStyleId(styleId);
        k.setColorId(color.getId());
        k.setSizeCode(size);
        k.setCode(SkuGenerator.skuCode(styleCode, color.getCode(), size));
        k.setStatus(1);
        return k;
    }

    static Color color(long id, String code, String name) {
        Color c = new Color();
        c.setId(id);
        c.setCode(code);
        c.setName(name);
        c.setStatus(1);
        return c;
    }

    static UserVO designer(long id, String nickname, String designerNo, boolean locked) {
        return new UserVO(id, "user" + id, nickname, null, 1, null, null, null, null, designerNo, locked);
    }

    private static TreeNode node(long id, Long parentId, String name) {
        return new TreeNode(id, parentId, null, name, parentId == null ? 1 : 2, 0, 1);
    }

    /** 让 serialNoGenerator.next 像真的一样按 key 递增，超过 99 报"流水号用完了" */
    void serialFrom(Map<String, Integer> current) {
        lenient().when(serialNoGenerator.next(anyString(), eq(StyleCodes.SEQ_WIDTH))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            int seq = current.merge(key, 1, Integer::sum);
            if (seq > 99) {
                throw new BizException("编号 " + key + " 的流水号用完了（最多 2 位）");
            }
            return SerialNoGenerator.format(key, seq, StyleCodes.SEQ_WIDTH);
        });
        lenient().when(serialNoGenerator.peek(anyString())).thenAnswer(inv -> current.getOrDefault(inv.<String>getArgument(0), 0) + 1);
    }

    /** 库里已经有这几个款号（手填、导入的）：styleMapper.exists(款号 = ?) 对它们返回 true */
    void codesTaken(String... taken) {
        List<String> list = List.of(taken);
        lenient().doAnswer(inv -> {
            AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();   // 条件的值是拼 SQL 时才登记进参数表的
            return wrapper.getParamNameValuePairs().values().stream().anyMatch(list::contains);
        }).when(styleMapper).exists(any());
    }
}
