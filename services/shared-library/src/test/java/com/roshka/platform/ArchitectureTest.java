package com.roshka.platform;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {

	@Test
	void technicalSupportDoesNotDependOnEitherService() {
		var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.roshka.platform");

		noClasses().should()
			.dependOnClassesThat()
			.resideInAnyPackage("com.roshka.order..", "com.roshka.inventory..")
			.check(classes);
	}

}
