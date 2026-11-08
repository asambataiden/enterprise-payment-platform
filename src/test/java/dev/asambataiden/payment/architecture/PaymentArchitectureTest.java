package dev.asambataiden.payment.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Dependency-direction rules for the independently deployable Payment Service. */
@AnalyzeClasses(packages = "dev.asambataiden.payment")
class PaymentArchitectureTest {

    @ArchTest
    static final ArchRule persistence_does_not_depend_on_web =
            noClasses().that().resideInAPackage("..payments.infrastructure.persistence..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.api..");

    @ArchTest
    static final ArchRule application_must_not_depend_on_http_adapters =
            noClasses().that().resideInAPackage("..payments.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.api..");

    @ArchTest
    static final ArchRule response_dtos_must_not_depend_on_persistence =
            noClasses().that().resideInAPackage("..payments.api.dto..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.infrastructure.persistence..");

    @ArchTest
    static final ArchRule controllers_do_not_access_persistence_directly =
            noClasses().that().resideInAPackage("..payments.api..")
                    .and().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.infrastructure.persistence..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_spring_web =
            noClasses().that().resideInAPackage("..payments.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework.web..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_database_adapters =
            noClasses().that().resideInAPackage("..payments.application..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.infrastructure..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_frameworks =
            noClasses().that().resideInAPackage("..payments.domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..", "jakarta.persistence..",
                            "..payments.api..", "..payments.infrastructure..");
}
