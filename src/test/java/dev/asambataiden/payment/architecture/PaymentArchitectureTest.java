package dev.asambataiden.payment.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Initial architectural guardrails. Stronger dependency-direction rules
 * will be introduced after domain models and API DTO mapping are decoupled.
 */
@AnalyzeClasses(packages = "dev.asambataiden.payment")
class PaymentArchitectureTest {

    @ArchTest
    static final ArchRule persistence_does_not_depend_on_web_or_application =
            noClasses()
                    .that().resideInAPackage("..payments.infrastructure.persistence..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("..payments.api..", "..payments.application..");

    @ArchTest
    static final ArchRule controllers_do_not_access_persistence_directly =
            noClasses()
                    .that().resideInAPackage("..payments.api..")
                    .and().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat()
                    .resideInAPackage("..payments.infrastructure.persistence..");
}
