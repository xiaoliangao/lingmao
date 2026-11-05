package com.lingmao.scm;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.Constraint;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.annotation.RepeatableContainers;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.AnnotatedArrayType;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bug 004 的另一半：校验注解都要写自己的中文 message。
 * 全局异常处理器只把 message 本身返回给前端，不再拼字段名；没写 message 的话，前端看到的是校验框架的默认提示
 * （"不能为空"、"个数必须在 0 和 64 之间"），不知道说的是哪个字段。
 * 扫描 com.lingmao.scm 下 src/main 编译出来的全部类，不只是 dto 包：Controller 方法参数上的 @Min 也要写。
 */
class ValidationMessageTest {

    /** 规则：src/main 里的每个校验注解都写了自己的 message（默认的 message 是 "{jakarta.validation...}"） */
    @Test
    void everyConstraintHasOwnMessage() throws Exception {
        List<Class<?>> classes = mainClasses();
        List<String> missing = new ArrayList<>();
        classes.forEach(type -> missing.addAll(missingMessages(type)));

        assertThat(classes).as("扫描没找到类，检查扫描本身").contains(ScmApplication.class, PageQuery.class);
        assertThat(missing).as("这些校验注解没写 message：写一句完整的中文，说清是哪个字段").isEmpty();
    }

    /** 检查本身要靠得住：故意漏写的几种位置都找得出来，写了 message 的不算 */
    @Test
    void missingMessages_findsEveryPlace() {
        assertThat(missingMessages(Sample.class)).containsExactlyInAnyOrder(
                "ValidationMessageTest.Sample.name @NotBlank",
                "ValidationMessageTest.Sample.tags @NotBlank",
                "ValidationMessageTest.Sample.code @Pattern",
                "ValidationMessageTest.Sample.find() 的参数 page @Min");
    }

    /** 一个类里没写 message 的约束注解，每条形如 "UserCreateReq.password @Size" */
    static List<String> missingMessages(Class<?> type) {
        String owner = ClassUtils.getShortName(type);
        // 用 Set 去重：@NotBlank 这类注解写在字段上，Java 同时把它算在字段的类型上，同一处会被看到两次
        Set<String> missing = new LinkedHashSet<>();
        collect(type, owner, missing);
        for (Field field : type.getDeclaredFields()) {
            String where = owner + "." + field.getName();
            collect(field, where, missing);
            collect(field.getAnnotatedType(), where, missing);
        }
        for (Method method : type.getDeclaredMethods()) {
            String where = owner + "." + method.getName() + "()";
            collect(method, where, missing);
            for (Parameter parameter : method.getParameters()) {
                String paramWhere = where + " 的参数 " + parameter.getName();
                collect(parameter, paramWhere, missing);
                collect(parameter.getAnnotatedType(), paramWhere, missing);
            }
        }
        return new ArrayList<>(missing);
    }

    /**
     * element 上的约束注解：重复写的（两个 @Pattern 会被编译成一个 @Pattern.List）拆开看；
     * List<@NotBlank String> 这种写在类型参数上的，顺着类型往里找
     */
    private static void collect(AnnotatedElement element, String where, Set<String> missing) {
        MergedAnnotations.from(element, SearchStrategy.DIRECT, RepeatableContainers.standardRepeatables()).stream()
                .filter(annotation -> annotation.getType().isAnnotationPresent(Constraint.class))
                .filter(annotation -> annotation.getString("message").startsWith("{"))
                .forEach(annotation -> missing.add(where + " @" + annotation.getType().getSimpleName()));
        if (element instanceof AnnotatedParameterizedType parameterized) {
            for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
                collect(argument, where, missing);
            }
        } else if (element instanceof AnnotatedArrayType array) {
            collect(array.getAnnotatedGenericComponentType(), where, missing);
        }
    }

    /** src/main 编译出来的类：和 ScmApplication 在同一个目录（或 jar）里的，测试类不算 */
    private static List<Class<?>> mainClasses() throws Exception {
        URL mainLocation = ScmApplication.class.getProtectionDomain().getCodeSource().getLocation();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);
        List<Class<?>> classes = new ArrayList<>();
        for (Resource resource : resolver.getResources("classpath*:com/lingmao/scm/**/*.class")) {
            String className = readerFactory.getMetadataReader(resource).getClassMetadata().getClassName();
            Class<?> type = ClassUtils.forName(className, ValidationMessageTest.class.getClassLoader());
            if (mainLocation.equals(type.getProtectionDomain().getCodeSource().getLocation())) {
                classes.add(type);
            }
        }
        return classes;
    }

    /** 故意漏写 message 的样本：字段、类型参数、重复的注解、方法参数，各一处；写了 message 的不该被报出来 */
    @SuppressWarnings("unused")
    static class Sample {

        @NotBlank
        private String name;

        @NotBlank(message = "备注不能为空")
        @Size(max = 255, message = "备注最长 255")
        private String remark;

        private List<@NotBlank String> tags;

        @Pattern(regexp = "^[a-z]+$")
        @Pattern(regexp = "^.{1,8}$", message = "编码最长 8 位")
        private String code;

        void find(@Min(1) int page) {
        }
    }
}
