package com.shitulelv.aicollab.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 组件扫描加载的 Spring 组件必须能被 Spring 唯一确定构造器，否则启动时会退回无参构造器并抛
 * {@code NoSuchMethodException: <init>()}。
 *
 * <p>判定规则与 Spring 的构造器解析一致：只有一个构造器；或恰好一个构造器标了 {@code @Autowired}
 * （或 {@code @Inject}）；或存在无参构造器。被 {@code @ConditionalOnProperty} 等条件关闭的组件
 * 在默认测试上下文里根本不会实例化，所以这里不依赖上下文加载，直接静态扫描字节码。</p>
 */
@AnalyzeClasses(packages = "com.shitulelv.aicollab",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ComponentConstructorGuardTest {

    private static final Set<String> INJECT_ANNOTATIONS = Set.of(
            Autowired.class.getName(),
            "jakarta.inject.Inject",
            "javax.inject.Inject");

    @ArchTest
    static final ArchRule springComponentsHaveResolvableConstructor = classes()
            .that().areMetaAnnotatedWith(Component.class)
            .and().areNotInterfaces()
            .and().areNotAnnotations()
            .and().doNotHaveModifier(JavaModifier.ABSTRACT)
            .should(haveResolvableConstructor())
            .because("多构造器的组件必须用 @Autowired 指明注入构造器，否则 Spring 会找无参构造器并启动失败");

    private static ArchCondition<JavaClass> haveResolvableConstructor() {
        return new ArchCondition<>("have a single constructor, exactly one @Autowired constructor, "
                + "or a no-arg constructor") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                Set<JavaConstructor> constructors = type.getConstructors();
                if (constructors.size() <= 1) {
                    return;
                }
                long autowired = constructors.stream()
                        .filter(ComponentConstructorGuardTest::isInjectionPoint)
                        .count();
                if (autowired == 1) {
                    return;
                }
                if (autowired == 0 && constructors.stream().anyMatch(c -> c.getRawParameterTypes().isEmpty())) {
                    return;
                }
                String signatures = constructors.stream()
                        .map(JavaConstructor::getFullName)
                        .sorted()
                        .collect(Collectors.joining(", "));
                events.add(SimpleConditionEvent.violated(type, type.getName() + " has "
                        + constructors.size() + " constructors and " + autowired
                        + " @Autowired constructors: " + signatures));
            }
        };
    }

    private static boolean isInjectionPoint(JavaConstructor constructor) {
        return constructor.getAnnotations().stream()
                .anyMatch(annotation -> INJECT_ANNOTATIONS.contains(annotation.getRawType().getName()));
    }
}
