package com.cognizant.emk.multiagent.infrastructure.web.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity contract between {@code openapi.yaml}'s {@code ProblemDetails.code} enum
 * and the {@code code} strings actually emitted by {@link GlobalExceptionHandler}
 * (US-14-003).
 *
 * <p>Three assertions:
 * <ol>
 *   <li>The handler does not emit any code outside the openapi enum (no leak).</li>
 *   <li>The openapi enum has no code the handler does not emit (no orphan).</li>
 *   <li>Every {@code code:} value found in any
 *       {@code components.responses.*.content.application/problem+json.examples.*.value.code}
 *       lives in the {@code ProblemDetails.code.enum} (no typo in examples).</li>
 * </ol>
 *
 * <p>The handler-emitted set is discovered by scanning the {@code GlobalExceptionHandler.java}
 * source for string-literal {@code code} arguments to {@code ProblemDetails.of(...)} and to
 * the private {@code body(...)} helper. AST scanning via regex over the source — pragmatic
 * and avoids invoking handler methods that need a real {@code HttpServletRequest}.
 */
class ProblemDetailsOpenApiContractTest {

    /** {@code body(HttpStatus.X, "CODE", "Title", ...)} — code is arg #2. */
    private static final Pattern BODY_HELPER = Pattern.compile(
            "body\\(\\s*HttpStatus\\.[A-Z_]+\\s*,\\s*\"([A-Z_]+)\"");

    /** {@code ProblemDetails.of("CODE", ...)} — code is arg #1. */
    private static final Pattern PROBLEM_DETAILS_OF = Pattern.compile(
            "ProblemDetails\\.of\\(\\s*\"([A-Z_]+)\"");

    @Test
    void handler_emits_no_code_outside_openapi_enum() throws IOException {
        Set<String> openapi = openapiEnum();
        Set<String> handler = handlerEmittedCodes();

        TreeSet<String> leak = new TreeSet<>(handler);
        leak.removeAll(openapi);

        assertThat(leak)
                .as("Codes emitted by GlobalExceptionHandler but NOT documented in "
                        + "openapi.yaml#/components/schemas/ProblemDetails/properties/code/enum. "
                        + "Add them to the openapi enum or remove the handler.")
                .isEmpty();
    }

    @Test
    void openapi_enum_has_no_code_unhandled() throws IOException {
        Set<String> openapi = openapiEnum();
        Set<String> handler = handlerEmittedCodes();

        TreeSet<String> orphans = new TreeSet<>(openapi);
        orphans.removeAll(handler);

        assertThat(orphans)
                .as("Codes documented in openapi.yaml's ProblemDetails.code.enum but NOT "
                        + "emitted by any @ExceptionHandler. Either the spec advertises a "
                        + "code the backend no longer emits, or a handler entry is missing.")
                .isEmpty();
    }

    @Test
    void every_openapi_response_example_code_is_in_the_enum() throws IOException {
        Set<String> enumCodes = openapiEnum();
        Set<String> exampleCodes = openapiResponseExampleCodes();

        TreeSet<String> stray = new TreeSet<>(exampleCodes);
        stray.removeAll(enumCodes);

        assertThat(stray)
                .as("Codes appearing in openapi.yaml response examples but not in the "
                        + "ProblemDetails.code.enum. Likely a typo in the example body.")
                .isEmpty();
    }

    @Test
    void contract_is_runnable() throws IOException {
        // Sanity check: the harness reads both sides and finds something. A previous
        // refactor moving openapi.yaml or the handler source would otherwise produce
        // green test results against empty sets.
        assertThat(openapiEnum()).isNotEmpty();
        assertThat(handlerEmittedCodes()).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Set<String> openapiEnum() throws IOException {
        Map<String, Object> root = loadOpenapi();
        Map<String, Object> components = (Map<String, Object>) root.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> problemDetails = (Map<String, Object>) schemas.get("ProblemDetails");
        Map<String, Object> properties = (Map<String, Object>) problemDetails.get("properties");
        Map<String, Object> codeField = (Map<String, Object>) properties.get("code");
        List<String> values = (List<String>) codeField.get("enum");
        return new TreeSet<>(values);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> openapiResponseExampleCodes() throws IOException {
        Map<String, Object> root = loadOpenapi();
        Map<String, Object> components = (Map<String, Object>) root.get("components");
        Map<String, Object> responses = (Map<String, Object>) components.get("responses");
        TreeSet<String> codes = new TreeSet<>();
        for (Object responseObj : responses.values()) {
            Map<String, Object> response = (Map<String, Object>) responseObj;
            Map<String, Object> content = (Map<String, Object>) response.get("content");
            if (content == null) {
                continue;
            }
            Map<String, Object> problemJson = (Map<String, Object>) content.get("application/problem+json");
            if (problemJson == null) {
                continue;
            }
            Map<String, Object> examples = (Map<String, Object>) problemJson.get("examples");
            if (examples == null) {
                continue;
            }
            for (Object exampleObj : examples.values()) {
                Map<String, Object> example = (Map<String, Object>) exampleObj;
                Map<String, Object> value = (Map<String, Object>) example.get("value");
                if (value == null) {
                    continue;
                }
                Object code = value.get("code");
                if (code instanceof String s) {
                    codes.add(s);
                }
            }
        }
        return codes;
    }

    private static Set<String> handlerEmittedCodes() throws IOException {
        Path source = handlerSourcePath();
        String body = Files.readString(source, StandardCharsets.UTF_8);
        TreeSet<String> codes = new TreeSet<>();
        collect(BODY_HELPER.matcher(body), codes);
        collect(PROBLEM_DETAILS_OF.matcher(body), codes);
        return codes;
    }

    private static void collect(Matcher matcher, Set<String> codes) {
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
    }

    private static Map<String, Object> loadOpenapi() throws IOException {
        Path openapi = openapiPath();
        try (var reader = Files.newBufferedReader(openapi, StandardCharsets.UTF_8)) {
            return new Yaml().load(reader);
        }
    }

    /**
     * Resolve {@code openapi.yaml} from the workspace root. The test runs from the
     * backend module, so the file is one level up. Falls back through a list of
     * candidate locations so the test is robust against differing working directories.
     */
    private static Path openapiPath() {
        List<Path> candidates = List.of(
                Path.of("..", "openapi.yaml"),
                Path.of("openapi.yaml"),
                Path.of("..", "..", "openapi.yaml"));
        return firstExisting(candidates, "openapi.yaml");
    }

    private static Path handlerSourcePath() {
        List<Path> candidates = List.of(
                Path.of("src", "main", "java",
                        "com", "cognizant", "emk", "multiagent",
                        "infrastructure", "web", "error", "GlobalExceptionHandler.java"),
                Path.of("backend", "src", "main", "java",
                        "com", "cognizant", "emk", "multiagent",
                        "infrastructure", "web", "error", "GlobalExceptionHandler.java"));
        return firstExisting(candidates, "GlobalExceptionHandler.java");
    }

    private static Path firstExisting(List<Path> candidates, String label) {
        List<String> tried = new ArrayList<>();
        for (Path p : candidates) {
            tried.add(p.toAbsolutePath().toString());
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(
                "Could not locate " + label + ". Tried: " + tried);
    }
}
