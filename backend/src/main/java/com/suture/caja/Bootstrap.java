package com.suture.caja;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Bootstrap implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    @Value("${caja.bootstrap.organization:}") private String organization;
    @Value("${caja.bootstrap.username:}") private String username;
    @Value("${caja.bootstrap.password:}") private String password;

    Bootstrap(JdbcTemplate db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }

    @Override @Transactional public void run(ApplicationArguments args) {
        Integer count = db.queryForObject("SELECT count(*) FROM organizations", Integer.class);
        if (count != null && count > 0) return;
        if (organization.isBlank() || username.isBlank() || password.length() < 12) {
            throw new IllegalStateException("Base vacía: configurar CAJA_BOOTSTRAP_ORGANIZATION, CAJA_BOOTSTRAP_USERNAME y CAJA_BOOTSTRAP_PASSWORD (12+ caracteres)");
        }
        Long org = db.queryForObject("INSERT INTO organizations(name) VALUES (?) RETURNING id", Long.class, organization.trim());
        db.update("INSERT INTO users(organization_id,username,password_hash,role) VALUES (?,?,?,'ADMINISTRADOR')", org, username.trim(), encoder.encode(password));
        db.update("INSERT INTO registers(organization_id,name) VALUES (?,'Caja principal')", org);
    }
}
