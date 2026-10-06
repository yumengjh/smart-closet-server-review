package com.smartcloset.workflow.persistence;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {
    @Bean(initMethod = "migrate")
    Flyway workflowMigrations(DataSource dataSource,
            @Value("${workflow.migration-location:classpath:db/migration/h2}") String location, com.smartcloset.processing.ProcessingMigrationBackup backup) {
        return Flyway.configure().dataSource(dataSource).locations(location).callbacks(backup).load();
    }

    @Bean
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        var interceptor = new MybatisPlusInterceptor();
        var pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
