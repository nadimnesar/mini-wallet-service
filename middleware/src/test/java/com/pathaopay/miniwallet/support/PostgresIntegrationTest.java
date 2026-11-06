package com.pathaopay.miniwallet.support;

import com.pathaopay.miniwallet.common.constant.AppConstants.Database;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

/**
 * Base for tests that need the full application on a real PostgreSQL started by Testcontainers
 * (Docker must be running). One container is shared by all test classes (singleton pattern) so
 * the cached Spring context keeps pointing at a live database. Both the write and read pools use
 * it, so read-only routing is exercised without a replica. Every test starts with empty tables.
 */
@SpringBootTest
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        for (String prefix : List.of(Database.WRITE_PROPERTIES_PREFIX, Database.READ_PROPERTIES_PREFIX)) {
            registry.add(prefix + ".jdbc-url", POSTGRES::getJdbcUrl);
            registry.add(prefix + ".username", POSTGRES::getUsername);
            registry.add(prefix + ".password", POSTGRES::getPassword);
        }
    }

    @Autowired
    protected JdbcClient jdbcClient;

    @BeforeEach
    void cleanDatabase() {
        jdbcClient.sql("TRUNCATE TABLE wallet_transactions, wallets").update();
    }
}
