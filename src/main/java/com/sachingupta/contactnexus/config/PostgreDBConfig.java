package com.sachingupta.contactnexus.config;

import com.sachingupta.contactnexus.service.ParameterStoreService;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@AllArgsConstructor
public class PostgreDBConfig {

    private static final Logger log = LoggerFactory.getLogger(PostgreDBConfig.class);

    private final PostgreDBProperties databaseProperties;
    private final ParameterStoreService parameterStoreService;

    @Bean
    public DataSource getDataSource() {
        return DataSourceBuilder.create()
                .url(resolve(databaseProperties.getUrl(), "db.url"))
                .username(resolve(databaseProperties.getUsername(), "db.username"))
                .password(resolve(databaseProperties.getPassword(), "db.password"))
                .driverClassName(databaseProperties.getDriverClassName())
                .build();
    }

    /**
     * Values that look like an SSM path (start with {@code /}) are dereferenced
     * through Parameter Store the way the AWS deployment expects. Anything else
     * is treated as the literal value; that lets the app run on Fly, Render,
     * local Docker, etc., with plain {@code DB_URL}/{@code DB_USERNAME}/{@code DB_PASSWORD}
     * env vars and no AWS credentials in scope.
     */
    private String resolve(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing database property " + propertyName);
        }
        if (value.startsWith("/")) {
            log.debug("Resolving {} from SSM parameter {}", propertyName, value);
            return parameterStoreService.getParameterValue(value);
        }
        return value;
    }
}
