package org.misarch.common.persistence

import io.r2dbc.spi.ConnectionFactory
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.transaction.ReactiveTransactionManager

/**
 * Persistence setup shared by all services:
 * - [MisarchNamingStrategy] (table = class name, column = property name),
 * - Flyway migrations over JDBC (`spring.flyway.url`, credentials from `spring.r2dbc.username/password`, i.e.
 *   env `SPRING_FLYWAY_URL`, `SPRING_R2DBC_USERNAME`, `SPRING_R2DBC_PASSWORD`),
 *   run before the application accepts requests; `spring.flyway.enabled=false` switches it off,
 * - reactive transactions ([R2dbcTransactionManager]) for `@Transactional` (also on `suspend` functions),
 * - [ConditionQueries] for [Condition] queries outside connections.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(ConnectionFactory::class)
class PersistenceConfiguration {

    @Bean
    @ConditionalOnMissingBean(NamingStrategy::class)
    fun misarchNamingStrategy(): NamingStrategy = MisarchNamingStrategy

    @Bean(initMethod = "migrate")
    @ConditionalOnProperty(name = ["spring.flyway.enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(Flyway::class)
    fun flyway(
        @Value("\${spring.flyway.url}") url: String,
        @Value("\${spring.flyway.user:\${spring.r2dbc.username:}}") user: String,
        @Value("\${spring.flyway.password:\${spring.r2dbc.password:}}") password: String,
        @Value("\${spring.flyway.locations:classpath:db/migration}") locations: Array<String>,
    ): Flyway = Flyway.configure()
        .dataSource(url, user, password)
        .locations(*locations)
        .baselineOnMigrate(true)
        .load()

    @Bean
    @ConditionalOnMissingBean(ReactiveTransactionManager::class)
    // services without a database (media: R2DBC auto-configuration excluded) have no ConnectionFactory
    @ConditionalOnBean(ConnectionFactory::class)
    fun transactionManager(connectionFactory: ConnectionFactory): R2dbcTransactionManager =
        R2dbcTransactionManager(connectionFactory)

    @Bean
    @ConditionalOnMissingBean(ConditionQueries::class)
    @ConditionalOnBean(R2dbcEntityTemplate::class)
    fun conditionQueries(entityTemplate: R2dbcEntityTemplate): ConditionQueries = ConditionQueries(entityTemplate)
}
