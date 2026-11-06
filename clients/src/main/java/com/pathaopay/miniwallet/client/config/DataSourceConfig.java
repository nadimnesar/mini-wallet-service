package com.pathaopay.miniwallet.client.config;

import com.pathaopay.miniwallet.common.constant.DbConstants;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;

/**
 * Read/write split across the PostgreSQL primary (write) and its streaming replica (read).
 *
 * <p>The application-wide {@link DataSource} is a {@link LazyConnectionDataSourceProxy}: it defers
 * fetching a physical connection until the first statement runs, by which point the transaction's
 * read-only flag is known. {@code @Transactional(readOnly = true)} work is served by the replica;
 * everything else (including non-transactional access) goes to the primary.
 *
 * <p>Replication is asynchronous, so reads that must observe a write made moments earlier
 * (e.g. balance checks before a debit) belong in a read-write transaction.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean(name = DbConstants.WRITE_BEAN, destroyMethod = "close")
    @ConfigurationProperties(DbConstants.WRITE_PROPERTIES_PREFIX)
    public HikariDataSource writeDataSource() {
        return new HikariDataSource();
    }

    @Bean(name = DbConstants.READ_BEAN, destroyMethod = "close")
    @ConfigurationProperties(DbConstants.READ_PROPERTIES_PREFIX)
    public HikariDataSource readDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier(DbConstants.WRITE_BEAN) DataSource writeDataSource,
                                 @Qualifier(DbConstants.READ_BEAN) DataSource readDataSource) {
        LazyConnectionDataSourceProxy routing = new LazyConnectionDataSourceProxy(writeDataSource);
        routing.setReadOnlyDataSource(readDataSource);
        return routing;
    }
}
