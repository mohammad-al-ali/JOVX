package com.jovx.api.security.internal.accounts;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;

/** Explicit isolated profile; never auto-runs DDL or uses a production connection. */
@Configuration(proxyBeanMethods = false)
@Profile("local-postgres")
public class LocalAccountConfiguration {
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(javax.sql.DataSource.class)
    HikariDataSource identityDataSource(Environment environment) {
        var settings = new HikariConfig();
        settings.setJdbcUrl(environment.getRequiredProperty("jovx.database.url"));
        settings.setConnectionInitSql(environment.getRequiredProperty("jovx.database.connection-init-sql"));
        settings.setUsername(environment.getRequiredProperty("jovx.database.username"));
        settings.setPassword(environment.getRequiredProperty("jovx.database.password"));
        settings.setMaximumPoolSize(environment.getProperty("jovx.database.pool-size", Integer.class, 4));
        settings.setConnectionTimeout(5000);
        settings.setPoolName("jovx-local-identity");
        return new HikariDataSource(settings);
    }

    @Bean SixDigitPinEncoder pinEncoder() { return new SixDigitPinEncoder(); }
    @Bean JdbcAccountStore accountStore(javax.sql.DataSource source, SixDigitPinEncoder encoder) {
        return new JdbcAccountStore(source, encoder);
    }
    @Bean DaoAuthenticationProvider accountAuthenticationProvider(JdbcAccountStore store, SixDigitPinEncoder encoder) {
        var provider = new DaoAuthenticationProvider(store);
        provider.setPasswordEncoder(encoder);
        return provider;
    }
}
