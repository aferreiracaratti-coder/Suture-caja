package com.suture.caja;

import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    public record Operator(long id, long organizationId, String username, String password, String role, boolean enabled) implements UserDetails {
        @Override public List<SimpleGrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_" + role)); }
        @Override public String getPassword() { return password; }
        @Override public String getUsername() { return username; }
        @Override public boolean isEnabled() { return enabled; }
    }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(JdbcTemplate db) {
        return username -> db.query("SELECT id, organization_id, username, password_hash, role, enabled FROM users WHERE username=?", rs -> {
            if (!rs.next()) throw new UsernameNotFoundException(username);
            return new Operator(rs.getLong("id"), rs.getLong("organization_id"), rs.getString("username"), rs.getString("password_hash"), rs.getString("role"), rs.getBoolean("enabled"));
        }, username);
    }

    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            .build();
    }
}
