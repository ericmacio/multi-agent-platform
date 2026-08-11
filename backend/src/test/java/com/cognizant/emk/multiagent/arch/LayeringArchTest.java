package com.cognizant.emk.multiagent.arch;

import com.cognizant.emk.multiagent.application.chat.DelegationServiceImpl;
import com.cognizant.emk.multiagent.domain.conversation.ConversationRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Codifies the hexagonal layering rule and the package-by-context convention.
 *
 * <p>Each rule is its own {@code @Test} so a failure points at exactly one violated invariant.
 * Test classes (anything under {@code src/test}) are excluded from the imported set so the
 * rules apply to production code only.
 */
class LayeringArchTest {

    private static final String ROOT = "com.cognizant.emk.multiagent";
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void domain_does_not_depend_on_application_or_infrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..");
        rule.check(productionClasses);
    }

    @Test
    void application_does_not_depend_on_infrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..");
        rule.check(productionClasses);
    }

    @Test
    void domain_does_not_depend_on_framework_packages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "com.fasterxml.jackson..",
                        "org.springframework.ai..",
                        "lombok..");
        rule.check(productionClasses);
    }

    @Test
    void application_does_not_use_spring_mvc_or_jpa() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "jakarta.persistence..",
                        "org.hibernate..");
        rule.check(productionClasses);
    }

    @Test
    void no_spring_ai_imports_in_application_chat() {
        // US-09-001: the LlmChatClient port and its companion records are the seam
        // between business code and Spring AI; Spring AI types MUST stay strictly
        // under infrastructure/llm/** so a future provider can replace OpenAI without
        // touching application or domain code (REQ-LLM-004 / REQ-ARC-005).
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application.chat..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.ai..");
        rule.check(productionClasses);
    }

    @Test
    void no_spring_imports_in_domain_conversation() {
        // US-10-001: the Conversation/Message aggregates, the ConversationOwner
        // sealed type, and the ConversationRepository port are strictly
        // Spring-/JPA-/JJWT-free. The broader rule
        // `domain_does_not_depend_on_framework_packages` already covers this
        // for the whole domain — this rule re-asserts it specifically for the
        // conversation subpackage so a future regression localized to
        // domain.conversation produces a focused failure message.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain.conversation..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..",
                        "jakarta.persistence..",
                        "org.hibernate..",
                        "io.jsonwebtoken..",
                        "com.fasterxml.jackson..",
                        "org.springframework.ai..",
                        "reactor..");
        rule.check(productionClasses);
    }

    @Test
    void no_rest_controllers_live_under_infrastructure_web_dev_on_main_classpath() {
        // US-CR1-002: dev-only smoke/probe controllers MUST live in src/test/java only.
        // Gating them by @Profile("dev") in src/main/java is not enough — running the
        // packaged JAR with SPRING_PROFILES_ACTIVE=dev would expose them.
        // The happy path of this rule is that the package itself does not exist on the
        // main classpath, so `allowEmptyShould(true)` is the intended state.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.web.dev..")
                .should().beAnnotatedWith(RestController.class)
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void no_rest_controllers_live_under_infrastructure_web_observability_on_main_classpath() {
        // US-15-004: the redaction-probe controller lives in src/test/java/
        // .../infrastructure/web/observability/ alongside the test-only
        // SensitiveDataRedactionIntegrationTest. The production observability
        // layer (CorrelationIdFilter, the JSON encoder configuration) does
        // not need any @RestController; this rule prevents a future probe
        // controller from sneaking onto the main classpath under that
        // package.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.web.observability..")
                .should().beAnnotatedWith(RestController.class)
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void security_adapters_must_not_call_Clock_systemUTC_directly() {
        // US-CR1-003: security-time-aware code (JJWT adapter, denylist adapter, …) MUST
        // read "now" from the Spring-managed Clock bean, not from Clock.systemUTC().
        // ClockConfig is the single point that calls systemUTC() and it lives under
        // infrastructure/config/, so this rule (scoped to infrastructure.security..)
        // does not need a special exemption.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.security..")
                .should().callMethod(Clock.class, "systemUTC")
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void bucket4j_imports_only_in_infrastructure_ratelimit() {
        // US-13-004: Bucket4j classes are an infrastructure adapter concern; the
        // application port (`RateLimitGate`) and the domain aggregate
        // (`RateLimitConfig`) MUST stay free of `io.github.bucket4j.*` imports so
        // a future provider (Hazelcast, Redis) can replace the in-JVM bucket
        // without touching business code (REQ-ARC-002 / REQ-RL-002).
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("..infrastructure.ratelimit..")
                .should().dependOnClassesThat().resideInAPackage("io.github.bucket4j..")
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void delegation_service_impl_does_not_depend_on_conversation_repository() {
        // US-12-002: REQ-AGT-015 — "B's exchanges with the LLM SHALL NOT be persisted —
        // neither into A's parent conversation, nor into a separate B-owned
        // conversation, nor into long-lived memory." The load-bearing structural
        // guarantee is that DelegationServiceImpl has no ConversationRepository
        // dependency at all; this rule forecloses a future refactor that would
        // accidentally persist a sub-agent turn.
        ArchRule rule = noClasses()
                .that().areAssignableTo(DelegationServiceImpl.class)
                .should().dependOnClassesThat().areAssignableTo(ConversationRepository.class);
        rule.check(productionClasses);
    }

    @Test
    void use_case_execution_exception_lives_only_under_application_shared() {
        // US-14-001: the application-layer 500 escape hatch is a single, cross-cutting
        // type. Per-context copies (e.g. agent.UseCaseExecutionException) would defeat
        // the intent — one typed seam for the handler's INTERNAL_ERROR path.
        ArchRule rule = classes()
                .that().haveSimpleName("UseCaseExecutionException")
                .should().resideInAPackage(ROOT + ".application.shared..");
        rule.check(productionClasses);
    }

    @Test
    void database_access_exception_lives_only_under_infrastructure_error() {
        // US-14-002: the DB-wrapping exception is a single infrastructure-layer type.
        // Per-adapter copies would dilute the contract the GlobalExceptionHandler
        // pins to a single 500 INTERNAL_ERROR mapping.
        ArchRule rule = classes()
                .that().haveSimpleName("DatabaseAccessException")
                .should().resideInAPackage(ROOT + ".infrastructure.error..");
        rule.check(productionClasses);
    }

    @Test
    void application_does_not_import_spring_dao() {
        // US-14-002: Spring's DataAccessException family (org.springframework.dao..)
        // must stay strictly inside infrastructure adapters. Letting it leak to
        // application code would re-introduce the Spring-typed orchestration the
        // hexagonal layering is meant to prevent (REQ-ARC-002 / REQ-ARC-007).
        ArchRule rule = noClasses()
                .that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.dao..");
        rule.check(productionClasses);
    }

    @Test
    void correlation_id_filter_lives_only_under_infrastructure_web_observability() {
        // US-15-002: the CorrelationIdFilter is a single cross-cutting type.
        // Per-context copies would defeat its purpose — one MDC key, one
        // response header, one filter ordering. The rule also implicitly
        // prevents future contributors from adding a sibling
        // ObservabilityFilter that duplicates the MDC contract.
        ArchRule rule = classes()
                .that().haveSimpleName("CorrelationIdFilter")
                .should().resideInAPackage(ROOT + ".infrastructure.web.observability..");
        rule.check(productionClasses);
    }

    @Test
    void observability_classes_do_not_depend_on_actuator_packages() {
        // US-15-003: the JSON encoder + CorrelationIdFilter must work on a
        // packaged JAR independently of the Actuator dependency. If a future
        // contributor wires the filter or encoder to depend on
        // org.springframework.boot.actuate.. or org.springframework.boot.health..,
        // the observability layer would no longer be ship-independent of
        // Actuator's classpath. The rule forbids both directions.
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.web.observability..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.boot.actuate..",
                        "org.springframework.boot.health..")
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void no_custom_health_indicators_under_infrastructure_web_dev() {
        // US-15-001: the v1 health contract is exactly the Spring-Boot default
        // DataSource + disk-space + ping composite. Custom HealthIndicator beans
        // under infrastructure.web.dev.. would be a dev-only escape hatch, which
        // is exactly what the existing dev-controllers rule already forbids on
        // the main classpath. This sibling rule extends that intent to health
        // indicators so a future contributor cannot add a leaky probe (e.g. a
        // DiagnosticHealthIndicator dumping connection strings) on the main
        // classpath under the assumption that "it's only dev".
        ArchRule rule = noClasses()
                .that().resideInAPackage("..infrastructure.web.dev..")
                .should().haveSimpleNameEndingWith("HealthIndicator")
                .allowEmptyShould(true);
        rule.check(productionClasses);
    }

    @Test
    void domain_classes_live_in_a_known_bounded_context() {
        ArchRule rule = classes()
                .that().resideInAPackage("..domain..")
                .should().resideInAnyPackage(
                        ROOT + ".domain",
                        ROOT + ".domain.shared",
                        ROOT + ".domain.shared..",
                        ROOT + ".domain.user",
                        ROOT + ".domain.user..",
                        ROOT + ".domain.agent",
                        ROOT + ".domain.agent..",
                        ROOT + ".domain.conversation",
                        ROOT + ".domain.conversation..",
                        ROOT + ".domain.tool",
                        ROOT + ".domain.tool..",
                        ROOT + ".domain.mcp",
                        ROOT + ".domain.mcp..",
                        ROOT + ".domain.ratelimit",
                        ROOT + ".domain.ratelimit..",
                        ROOT + ".domain.auth",
                        ROOT + ".domain.auth..");
        rule.check(productionClasses);
    }
}
