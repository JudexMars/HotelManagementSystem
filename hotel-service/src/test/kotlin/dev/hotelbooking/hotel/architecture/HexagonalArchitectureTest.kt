package dev.hotelbooking.hotel.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

class HexagonalArchitectureTest {
    @Test fun `core depends only on core and language libraries`() {
        val code =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("dev.hotelbooking.hotel")
        classes()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                "..domain..",
                "..application..",
                "java..",
                "kotlin..",
                "org.jetbrains.annotations..",
            ).check(code)
    }

    @Test fun `core cannot use JDK database or network clients`() {
        val code =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("dev.hotelbooking.hotel")
        noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.sql..", "javax.sql..", "java.net..")
            .check(code)
    }

    @Test fun `domain does not depend on application orchestration`() {
        val code =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("dev.hotelbooking.hotel")
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..application..")
            .check(code)
    }

    @Test fun `http adapters cannot bypass application through persistence`() {
        val code =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("dev.hotelbooking.hotel")
        noClasses()
            .that()
            .resideInAPackage("..api..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..")
            .check(code)
    }
}
