package com.aicompany.backend.blueprint;

import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import com.aicompany.backend.blueprint.scan.BlueprintScanner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-62): an endpoint's authorization in the Blueprint comes from the
 * rules by path of the security configuration too, not only from annotations --
 * the first rule that matches its method and route, as Spring Security decides.
 */
class BlueprintAccessRulesTest {

    private static BlueprintScanner.Result result;

    @BeforeAll
    static void scan() {
        String base = "server/src/main/java/com/acme/";
        result = BlueprintScanner.withDefaultPlugins().analyze(List.of(
                new SourceFile(base + "security/SecurityConfig.java", """
                        package com.acme.security;

                        import org.springframework.http.HttpMethod;

                        public class SecurityConfig {
                            private static final String[] ADMIN_ONLY = {"/api/admin/**"};

                            SecurityFilterChain chain(HttpSecurity http) throws Exception {
                                http.authorizeHttpRequests(auth -> auth
                                        .requestMatchers("/api/public/**").permitAll()
                                        .requestMatchers(ADMIN_ONLY).hasRole("ADMIN")
                                        .requestMatchers(HttpMethod.DELETE, "/api/tasks/*").hasRole("ADMIN")
                                        .requestMatchers("/api/tasks/**").hasAnyRole("ADMIN", "OPERATOR")
                                        .requestMatchers(Elsewhere.PATHS).denyAll()
                                        .anyRequest().authenticated());
                                return http.build();
                            }
                        }
                        """),
                new SourceFile(base + "task/TaskController.java", """
                        package com.acme.task;

                        import org.springframework.security.access.prepost.PreAuthorize;
                        import org.springframework.web.bind.annotation.*;

                        @RestController
                        public class TaskController {
                            @GetMapping("/api/tasks/{id}")
                            public String one(@PathVariable Long id) { return ""; }

                            @DeleteMapping("/api/tasks/{id}")
                            public void delete(@PathVariable Long id) { }

                            @GetMapping("/api/admin/users")
                            public String users() { return ""; }

                            @GetMapping("/api/public/info")
                            public String info() { return ""; }

                            @GetMapping("/api/reports")
                            public String reports() { return ""; }

                            @PreAuthorize("hasRole('AUDITOR')")
                            @GetMapping("/api/audit")
                            public String audit() { return ""; }
                        }
                        """)), false);
    }

    private static String authorization(String name) {
        return result.components().stream().filter(c -> c.kind() == NodeKind.ENDPOINT && c.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no endpoint " + name)).structure().authorization();
    }

    @Test
    void theFirstRuleThatMatchesTheMethodAndTheRouteDecides() {
        assertThat(authorization("DELETE /api/tasks/{id}"))
                .startsWith("hasRole('ADMIN') — regola per percorso DELETE /api/tasks/*")
                .contains("SecurityConfig.java:");
        assertThat(authorization("GET /api/tasks/{id}")).startsWith("hasAnyRole('ADMIN', 'OPERATOR')");
    }

    @Test
    void aConstantOfTheSameFileIsReadAndAnyRequestIsTheLastWord() {
        assertThat(authorization("GET /api/admin/users")).startsWith("hasRole('ADMIN') — regola per percorso /api/admin/**");
        assertThat(authorization("GET /api/public/info")).startsWith("permitAll()");
        assertThat(authorization("GET /api/reports")).startsWith("authenticated() — regola per percorso /**");
    }

    @Test
    void anAnnotationStillDecidesWhereThereIsOne() {
        assertThat(authorization("GET /api/audit")).isEqualTo("hasRole('AUDITOR')");
    }

    @Test
    void routeVariablesAndWildcardsMatchLikeSpring() {
        assertThat(BlueprintScanner.pathMatches("/api/projects/*/blueprint", "/api/projects/{id}/blueprint")).isTrue();
        assertThat(BlueprintScanner.pathMatches("/api/admin/**", "/api/admin")).isTrue();
        assertThat(BlueprintScanner.pathMatches("/api/tasks/*", "/api/tasks/{id}/runs")).isFalse();
        assertThat(BlueprintScanner.pathMatches("/api/tasks/7", "/api/tasks/{id}")).isFalse();
        assertThat(BlueprintScanner.pathMatches("/files/*.json", "/files/plan.json")).isTrue();
    }
}
