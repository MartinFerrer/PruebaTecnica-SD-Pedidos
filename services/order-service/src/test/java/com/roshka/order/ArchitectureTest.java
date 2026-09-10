package com.roshka.order;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void coreDoesNotDependOnFrameworksOrAdapters() {
    var classes =
        new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.roshka.order");
    noClasses().that().resideInAnyPackage("..domain..", "..application..").should().dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta..",
            "tools.jackson..",
            "com.fasterxml..",
            "..adapter..",
            "..configuration..").check(classes);
    noClasses().should().dependOnClassesThat().resideInAPackage("com.roshka.inventory..").check(classes);
  }
}
