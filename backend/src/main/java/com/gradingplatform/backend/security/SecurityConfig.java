package com.gradingplatform.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JWT filter chain (2.4b). Stateless: no session, no cookie, no login form; each request
 * carries `Authorization: Bearer <token>`, which {@link JwtAuthenticationFilter} verifies.
 *
 * <p>Public: `GET /health`, `POST /auth/signup`, `POST /auth/signin` and Spring's `/error` page.
 * Everything else needs a valid token; later tasks add role rules on top.
 *
 * <p>Who may call what (2.5a): the chain only decides signed in or not. The role rule of an
 * endpoint is a `@PreAuthorize` on its controller method, e.g. `@PreAuthorize("hasRole('ADMIN')")`
 * or `@PreAuthorize("hasAnyRole('TEACHER', 'ADMIN')")`, so the rule sits next to the code it guards.
 * Roles are listed explicitly: there is no role hierarchy, `ADMIN` does not imply `TEACHER`. A
 * signed-in caller with the wrong role gets 403, an anonymous one 401. Do not add URL matchers by
 * role here.
 *
 * <p>CSRF protection is off. It defends against a browser attaching ambient credentials (a session
 * or cookie) to a forged request; the token here is only ever sent by our own client code in a
 * header, never as a cookie (2.4a), so there is nothing for a forged request to ride on.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, JsonMapper jsonMapper)
            throws Exception {
        var writer = new JsonErrorWriter(jsonMapper);
        http.csrf(AbstractHttpConfigurer::disable)
                // Uses the `corsConfigurationSource` bean (config/CorsConfig). It answers preflight
                // requests itself, ahead of the authentication filter.
                .cors(Customizer.withDefaults())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable())
                // No token, or one that does not verify: 401, no redirect to a login page and no
                // `WWW-Authenticate` challenge. A wrong role is 403. Both carry the standard error
                // body (2.6a), written here because these answers never reach the MVC handler.
                .exceptionHandling(errors -> errors.authenticationEntryPoint(new JsonAuthenticationEntryPoint(writer))
                        .accessDeniedHandler(new JsonAccessDeniedHandler(writer)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests
                        // Spring MVC reports an error status (400, 401, 409...) by forwarding to
                        // /error, which this chain also guards. Without this every error answered
                        // to an anonymous caller (signup, signin) would turn into a 403.
                        .requestMatchers("/error")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/signup", "/auth/signin")
                        .permitAll()
                        .anyRequest()
                        .authenticated());
        return http.build();
    }
}
