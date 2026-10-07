package com.smartcloset.management;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

/** The management module is a removable plug-in: one-way dependency and every bean behind the switch. */
class ManagementArchitectureTest {
    static final JavaClasses CLASSES = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.smartcloset");

    @Test void coreNeverDependsOnManagement() {
        noClasses().that().resideOutsideOfPackage("com.smartcloset.management..")
                .should().dependOnClassesThat().resideInAPackage("com.smartcloset.management..")
                .check(CLASSES);
    }

    @Test void everyManagementBeanIsConditional() {
        classes().that().resideInAPackage("com.smartcloset.management..")
                .and().areMetaAnnotatedWith(Component.class)
                .should().beAnnotatedWith(ConditionalOnManagementEnabled.class)
                .check(CLASSES);
    }
}
