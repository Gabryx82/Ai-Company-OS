package com.aicompany.backend.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    @Bean
    ApiTokenRegistry apiTokenRegistry(ApiTokenProperties properties) {
        return new ApiTokenRegistry(properties.getApiTokens());
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
                                    ApiTokenRegistry registry,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {

        AuthenticationEntryPoint entryPoint = new ProblemAuthenticationEntryPoint(resolver);

        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        // The error dispatch of a request that was already
                        // authorised or already refused: not a way in.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterBefore(new BearerTokenAuthenticationFilter(registry, entryPoint),
                        AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
