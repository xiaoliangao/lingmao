package com.lingmao.scm;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 架构规则（RFC 001 §11.1、架构设计 §3）：读 src/main 编译出来的类，不启动 Spring。
 * 违反了 mvn test 就失败，失败信息里列出是哪个类的哪个字段、哪个方法用了不该用的东西。
 */
@AnalyzeClasses(packages = "com.lingmao.scm", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String MODULE = "com.lingmao.scm.module";

    /** 公共的域：每个业务域都能用，架构图里不画 */
    private static final Set<String> SHARED = Set.of("system", "biz");

    /**
     * 每个域直接依赖谁，照架构设计 §3.2 的图抄，图改了这张表跟着改。
     * 间接的不用写：production → order → techpack，production 就能直接用 techpack，下面会算出来。
     * biz 可以用 system；system 在最底下，不依赖别的域
     */
    private static final Map<String, Set<String>> DIRECT = Map.ofEntries(
            entry("board", Set.of("production", "purchase")),
            entry("production", Set.of("mrp", "order", "inventory")),
            entry("purchase", Set.of("mrp", "inventory", "supplier")),
            entry("mrp", Set.of("order", "inventory")),
            entry("order", Set.of("techpack")),
            entry("techpack", Set.of("style")),
            entry("style", Set.of("supplier", "basic")),
            entry("supplier", Set.of("basic")),
            entry("inventory", Set.of("basic")),
            entry("basic", Set.of()),
            entry("biz", Set.of("system")),
            entry("system", Set.of()));

    /** 每个域能用哪些域：直接的、间接的，业务域再加上 system、biz */
    private static final Map<String, Set<String>> ALLOWED = allowed(DIRECT);

    /** 规则 1：业务域之间没有环。A 用 B、B 又用 A，两个就拆不开了，改一个要连另一个一起测 */
    @ArchTest
    static final ArchRule noCycleBetweenDomains = slices().matching(MODULE + ".(*)..")
            .should().beFreeOfCycles();

    /** 规则 2：只能朝表里的方向依赖（下游用上游、单据用主数据），反过来不行 */
    @ArchTest
    static final ArchRule dependOnlyDownward = classes().that().resideInAPackage(MODULE + "..")
            .should(onlyUseAllowedDomains());

    /** 规则 2 用的表本身也要对：表里提到的域都有自己的一行；不能有环（改表时把图改出了环，这里马上红） */
    @ArchTest
    static void dependencyTableIsValid(JavaClasses ignored) {
        DIRECT.forEach((domain, deps) -> {
            assertThat(DIRECT.keySet()).as("%s 依赖的域在表里要有自己的一行", domain).containsAll(deps);
            assertThat(ALLOWED.get(domain)).as("依赖表里有环：%s 绕一圈又依赖回了自己", domain).doesNotContain(domain);
        });
    }

    /** 规则 3：controller 不直接用 mapper。业务规则、事务都在 service 里，绕过去就都漏了 */
    @ArchTest
    static final ArchRule controllerNoMapper = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..mapper..");

    /** 规则 4：跨域只能用对方的 service 接口、dto、entity；mapper、service.impl、controller 是对方的内部，不能碰 */
    @ArchTest
    static final ArchRule crossDomainOnlyServiceDtoEntity = classes().that().resideInAPackage(MODULE + "..")
            .should(useOtherDomainsOnlyThroughServiceDtoEntity());

    /** 规则 5：common 是和业务无关的框架代码，不依赖业务域，也不依赖 config、security */
    @ArchTest
    static final ArchRule commonDependsOnNothingOfOurs = noClasses().that().resideInAPackage("com.lingmao.scm.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    MODULE + "..", "com.lingmao.scm.config..", "com.lingmao.scm.security..");

    private static ArchCondition<JavaClass> onlyUseAllowedDomains() {
        return new ArchCondition<>("只用依赖表里允许的域") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = domainOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String to = domainOf(dependency.getTargetClass());
                    if (to == null || to.equals(from)) {
                        continue;
                    }
                    if (!ALLOWED.containsKey(from)) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                "域 " + from + " 不在依赖表里，先写进 ArchitectureTest.DIRECT：" + dependency.getDescription()));
                    } else if (!ALLOWED.get(from).contains(to)) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                from + " 不能依赖 " + to + "：" + dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> useOtherDomainsOnlyThroughServiceDtoEntity() {
        return new ArchCondition<>("跨域只用对方的 service 接口、dto、entity") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String from = domainOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String to = domainOf(target);
                    if (to == null || to.equals(from)) {
                        continue;
                    }
                    String base = MODULE + "." + to + ".";
                    String pkg = target.getPackageName();
                    // service 只认接口所在的那一层，service.impl 不算
                    boolean open = pkg.equals(base + "service") || isIn(pkg, base + "dto") || isIn(pkg, base + "entity");
                    if (!open) {
                        events.add(SimpleConditionEvent.violated(dependency,
                                "跨域只能用 " + to + " 的 service 接口、dto、entity：" + dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * 类属于哪个域：com.lingmao.scm.module.<域>... 里的 <域>；不在 module 下的返回 null。
     * 数组本身不算：ArchUnit 对数组的元素类另记了一条依赖，按那条查，不然同一处会报两遍
     */
    private static String domainOf(JavaClass javaClass) {
        if (javaClass.isArray()) {
            return null;
        }
        String pkg = javaClass.getPackageName();
        if (!pkg.startsWith(MODULE + ".")) {
            return null;
        }
        String rest = pkg.substring(MODULE.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static boolean isIn(String pkg, String parent) {
        return pkg.equals(parent) || pkg.startsWith(parent + ".");
    }

    /**
     * 从直接依赖一层层往下找，得出每个域能用的全部域。表错了这里不抛异常：静态字段初始化时抛的话，
     * 只能看到"archunit 找不到测试"，真正的原因藏在 surefire 的 dump 文件里；交给 dependencyTableIsValid 报
     */
    private static Map<String, Set<String>> allowed(Map<String, Set<String>> direct) {
        Map<String, Set<String>> result = new HashMap<>();
        direct.forEach((domain, deps) -> {
            Set<String> reachable = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(deps);
            while (!todo.isEmpty()) {
                String next = todo.pop();
                if (reachable.add(next)) {
                    todo.addAll(direct.getOrDefault(next, Set.of()));
                }
            }
            if (!SHARED.contains(domain)) {
                reachable.addAll(SHARED);
            }
            result.put(domain, reachable);
        });
        return result;
    }
}
