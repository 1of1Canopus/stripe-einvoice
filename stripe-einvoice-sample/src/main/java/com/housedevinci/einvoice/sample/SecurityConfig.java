package com.housedevinci.einvoice.sample;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic over everything.
 *
 * <p>The sample's endpoints read and write a legal numbering series, so none of them is open. A
 * host application that copies this file should replace the authentication, never remove it.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            requests ->
                requests
                    // The webhook endpoint authenticates its caller with an HMAC over the exact
                    // bytes it received, against a keyring this application configures. Putting
                    // HTTP Basic in front of it would not make it safer; it would make Stripe's
                    // deliveries fail. Everything else stays authenticated.
                    .requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/stripe")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .httpBasic(basic -> {})
        // No session, no form login, and no CSRF token to carry: every caller authenticates on
        // every request, and the webhook caller authenticates with a signature over its body.
        .csrf(csrf -> csrf.disable())
        .build();
  }
}
