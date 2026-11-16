package com.jovx.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularArchitectureTest {
    @Test void partnerMvcKeepsSqlAndTransportOutOfBusinessModels() {
        var classes=new com.tngtech.archunit.core.importer.ClassFileImporter().importPackages("com.jovx.api.partners");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideInAnyPackage("..partners.controller..","..partners.service..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..","java.sql..","javax.sql..").check(classes);
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses().that().resideInAPackage("..partners.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..partners.controller..","..partners.service..","..partners.repository..","..partners.dto..").check(classes);
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
