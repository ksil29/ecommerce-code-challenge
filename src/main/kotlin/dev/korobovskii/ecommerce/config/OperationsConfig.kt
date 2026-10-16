package dev.korobovskii.ecommerce.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class OperationsConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )

    @Bean
    fun openApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Shop API")
            .version("v1")
            .description(
                "Catalog, CSV import, cart pricing and checkout. State-changing requests need the X-XSRF-TOKEN header " +
                    "with the value of the XSRF-TOKEN cookie; admin endpoints need a session from POST /api/auth/login.",
            ),
    )
}
