package com.aicompany.backend.security;

import com.aicompany.backend.user.service.SecurityLog;
import com.aicompany.backend.user.service.SessionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.List;

/**
 * The security of the control plane, in one chain (ADR-013).
 *
 * <ul>
 *   <li><strong>Stateless.</strong> No session, no cookie: every request carries
 *       its credential. That is also why CSRF protection is off -- CSRF abuses a
 *       credential the browser attaches by itself, and a bearer token is never
 *       attached by the browser.</li>
 *   <li><strong>Two kinds of caller, two roles</strong> (PHASE 15, ADR-024). People
 *       sign in and get a session token; machines keep configured tokens. Both
 *       travel as bearer tokens in a header, so the stateless reasoning above
 *       still holds. {@code OPERATOR} works; {@code ADMIN} also deletes, manages
 *       people and reads the security log -- the routes are listed below, in one
 *       place, and {@code AuthorizationContractTest} holds them.</li>
 *   <li><strong>Deny by default.</strong> The only public route is the liveness
 *       probe. Everything else, including paths that do not exist, needs a
 *       principal -- so an anonymous caller cannot map the API by comparing 404
 *       with 401.</li>
 *   <li><strong>No login form, no Basic, no logout.</strong> Boot's defaults would
 *       add all three, and each is a second way in that nobody decided on.</li>
 *   <li><strong>CORS before authentication</strong> (TASK-014): a preflight never
 *       carries a credential, so it is answered by the CORS filter from the declared
 *       origins alone, and the request that follows is authenticated like any
 *       other. See {@link CorsPolicy}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiTokenProperties.class)
public class SecurityConfiguration {

    /** Routes only an admin may call, whatever the method. */
    public static final String[] ADMIN_ONLY = {"/api/admin/**", "/api/terminal/**"};

    /** Deletes that destroy data for real (PHASE 20): distinct from archiving, and an admin's decision. */
    public static final String[] ADMIN_ONLY_DELETES = {"/api/projects/*", "/api/tasks/*"};

    @Bean
    ApiTokenRegistry apiTokenRegistry(ApiTokenProperties properties) {
        return new ApiTokenRegistry(properties.getApiTokens());
    }

    /** BCrypt, cost 12: about a quarter of a second per check on this class of machine (ADR-024 §2). */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CallerResolver callerResolver(ApiTokenRegistry registry, ApiTokenProperties properties, SessionService sessions) {
        return new CallerResolver(registry, properties.getApiTokenRoles(), sessions);
    }

    @Bean
    CorsPolicy corsPolicy(@Value("${aicos.cors.allowed-origins:}") List<String> allowedOrigins) {
        return CorsPolicy.from(allowedOrigins);
    }

    /** Picked up by {@code http.cors(...)} below: Spring Security looks for this bean. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsPolicy policy) {
        return policy.toSource();
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    CallerResolver callers,
                                    SecurityLog securityLog,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {

        AuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(resolver);
        AccessDeniedHandler denied = (request, response, exception) -> {
            securityLog.record(SecurityLog.ACCESS_DENIED,
                    request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName(),
                    request.getRemoteAddr(), request.getMethod() + " " + request.getRequestURI());
            resolver.resolveException(request, response, null, exception);
        };

        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        // How a person gets a credential: the only public write.
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        // The error dispatch of a request that was already
                        // authorised or already refused: not a way in.
                        .requestMatchers("/error").permitAll()
                        // ADMIN only (ADR-024 §3): people, the security log, the
                        // ecosystem's process configuration, the terminal, and
                        // every destructive delete.
                        .requestMatchers(ADMIN_ONLY).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, ADMIN_ONLY_DELETES).hasRole("ADMIN")
                        // What the launcher may execute is an admin's decision (PHASE 21).
                        .requestMatchers(HttpMethod.POST, "/api/software").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/software/*").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .addFilterBefore(new BearerTokenAuthenticationFilter(callers, entryPoint),
                        AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
