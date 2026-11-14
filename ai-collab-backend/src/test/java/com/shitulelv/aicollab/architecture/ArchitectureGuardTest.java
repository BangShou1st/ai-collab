package com.shitulelv.aicollab.architecture;

import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import com.shitulelv.aicollab.project.domain.policy.DatabaseProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.policy.DatabaseProjectWriteGuard;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectMemberRepository;
import com.shitulelv.aicollab.project.infrastructure.repository.ProjectRepository;
import com.shitulelv.aicollab.work.domain.policy.WorkPermissionPolicy;
import com.shitulelv.aicollab.work.infrastructure.entity.TaskEntity;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 最小架构护栏。历史 domain→infrastructure 违规逐条列明，只冻结这四个既有依赖；
 * 其他新增依赖会立即失败。
 */
@AnalyzeClasses(packages = "com.shitulelv.aicollab",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureGuardTest {

    @ArchTest
    static final ArchRule domainMustNotDependOnInfrastructure = classes()
            .that().resideInAPackage("..domain..")
            .should(noNewInfrastructureDependencies())
            .because("domain 只允许依赖自身与 common；历史四条例外已显式冻结");

    @ArchTest
    static final ArchRule controllersMustNotUseMappers = noClasses()
            .that().resideInAPackage("..api.controller..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Mapper")
            .because("Controller 只能调用 Application Service，不能直连数据层");

    @ArchTest
    static final ArchRule repositoryInterfacesStayInRepositoryAdapters = classes()
            .that().areInterfaces().and().haveSimpleNameEndingWith("Repository")
            .should().resideInAnyPackage("..infrastructure.repository..")
            .because("Repository 接口必须位于 infrastructure/repository 适配层");

    private static final Set<String> HISTORICAL_DOMAIN_INFRASTRUCTURE_DEPENDENCIES = Set.of(
            dependency(WorkPermissionPolicy.class, TaskEntity.class),
            dependency(com.shitulelv.aicollab.document.domain.service.DocumentChunker.class,
                    ParsedDocument.class),
            dependency(com.shitulelv.aicollab.document.domain.service.DocumentChunker.class,
                    ParsedDocument.PageBoundary.class),
            dependency(DatabaseProjectWriteGuard.class, ProjectRepository.class),
            dependency(DatabaseProjectAccessGuard.class, ProjectMemberRepository.class));

    private static ArchCondition<JavaClass> noNewInfrastructureDependencies() {
        return new ArchCondition<>("not depend on infrastructure except the four frozen historical edges") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                origin.getDirectDependenciesFromSelf().stream()
                        .filter(dependency -> isInfrastructure(dependency.getTargetClass()))
                        .filter(dependency -> !HISTORICAL_DOMAIN_INFRASTRUCTURE_DEPENDENCIES.contains(
                                dependencyName(dependency.getOriginClass(), dependency.getTargetClass())))
                        .forEach(dependency -> events.add(SimpleConditionEvent.violated(
                                origin,
                                origin.getName() + " depends on " + dependency.getTargetClass().getName())));
            }
        };
    }

    private static boolean isInfrastructure(JavaClass type) {
        String packageName = type.getPackageName();
        return packageName.endsWith(".infrastructure")
                || packageName.contains(".infrastructure.");
    }

    private static String dependency(Class<?> origin, Class<?> target) {
        return dependencyName(origin.getName(), target.getName());
    }

    private static String dependencyName(JavaClass origin, JavaClass target) {
        return dependencyName(origin.getName(), target.getName());
    }

    private static String dependencyName(String origin, String target) {
        return origin + " -> " + target;
    }
}
