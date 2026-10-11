package com.sachingupta.contactnexus.config;

import com.sachingupta.contactnexus.service.ParameterStoreService;
import lombok.AllArgsConstructor;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@AllArgsConstructor
public class PostgreDBConfig {

    private final PostgreDBProperties databaseProperties;
    private final ParameterStoreService parameterStoreService;

    @Bean
    public DataSource getDataSource() {
        String dbUrl = parameterStoreService.getParameterValue(databaseProperties.getUrl());
        String dbUsername = parameterStoreService.getParameterValue(databaseProperties.getUsername());
        String dbPassword = parameterStoreService.getParameterValue(databaseProperties.getPassword());

        return DataSourceBuilder.create()
                .url(dbUrl)
                .username(dbUsername)
                .password(dbPassword)
                .driverClassName(databaseProperties.getDriverClassName())
                .build();
    }
}
