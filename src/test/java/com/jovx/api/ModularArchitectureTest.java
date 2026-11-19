package com.jovx.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

class ModularArchitectureTest {
    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
        new ClassFileImporter().importPackages("com.jovx.api");

    /** Business modules that follow controller/service/repository/model/dto layering. */
    private static final List<String> MODULES = List.of("partners", "membership", "points", "releases", "wallets");

    /** The only endpoints reachable without a role/permission check (decided by the product owner). */
    private static final Set<String> PUBLIC_OR_SESSION_ONLY = Set.of(
        "AuthController#login", "AuthController#refresh", "AuthController#logout",
        "SessionController#session",
        "MemberController#claim", "MemberController#publicPlans",
        "PublicPartnerController#partners",
        "ReleaseController#check");

    @Test void everyModuleKeepsSqlInRepositoriesAndTransportOutOfModels() {
        for (String m : MODULES) {
            noClasses().that().resideInAnyPackage("..api." + m + ".controller..", "..api." + m + ".service..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .allowEmptyShould(true).check(CLASSES);
            noClasses().that().resideInAPackage("..api." + m + ".controller..")
                .should().dependOnClassesThat().resideInAPackage("..api." + m + ".repository..")
                .because("controllers talk to services and return DTOs, never storage types")
                .allowEmptyShould(true).check(CLASSES);
            noClasses().that().resideInAPackage("..api." + m + ".model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "..api." + m + ".controller..", "..api." + m + ".service..",
                    "..api." + m + ".repository..", "..api." + m + ".dto..")
                .allowEmptyShould(true).check(CLASSES);
        }
    }

    @Test void businessServicesDoNotDependOnTheWebLayer() {
        for (String m : MODULES)
            noClasses().that().resideInAPackage("..api." + m + ".service..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "org.springframework.http..")
                .because("services raise DomainException; ApiErrors maps it to HTTP")
                .allowEmptyShould(true).check(CLASSES);
    }

    /** Security requires login for every API route; each endpoint must state its own authorization. */
    @Test void everyEndpointDeclaresItsAuthorization() throws Exception {
        var unguarded = new TreeSet<String>();
        for (var javaClass : CLASSES) {
            if (!javaClass.isAnnotatedWith(RestController.class)) continue;
            Class<?> type = Class.forName(javaClass.getName());
            boolean classGuard = AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class);
            for (var method : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) continue;
                String name = type.getSimpleName() + "#" + method.getName();
                if (!classGuard && !AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)
                    && !PUBLIC_OR_SESSION_ONLY.contains(name)) unguarded.add(name);
            }
        }
        assertEquals(Set.of(), unguarded, "Endpoints without @PreAuthorize must be explicitly approved as public");
    }

    @Test
    void modulesAreDiscoveredAndRespectTheirDeclaredBoundaries() {
        var modules = ApplicationModules.of(JovxApiApplication.class);
        assertTrue(modules.getModuleByName("security").isPresent(),
            "The security module must participate in architecture verification");
        assertTrue(modules.getModuleByName("partners").isPresent());
        assertTrue(modules.getModuleByName("identity").isEmpty(),
            "The old identity package must not survive as a separate module");
        modules.verify();
    }
}
