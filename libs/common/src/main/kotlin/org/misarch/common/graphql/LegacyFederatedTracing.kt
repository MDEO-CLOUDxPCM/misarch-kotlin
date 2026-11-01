package org.misarch.common.graphql

import com.apollographql.federation.graphqljava.tracing.FederatedTracingInstrumentation
import graphql.ExecutionInput
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Apollo federated tracing (`extensions.ftv1` in every GraphQL response) as in the **original Kotlin services**.
 *
 * The originals (graphql-kotlin 7 + federation-jvm 4.1.0) traced every request whose GraphQL context has no
 * `apollo-federation-include-trace` entry, i.e. every request from the MiSArch gateway: each response carries a
 * base64 protobuf trace (`extensions.ftv1`, observed on the original tax and shipment services on 2026-10-13).
 * federation-jvm 7 (graphql-kotlin 11) traces only if that entry is present and equals `ftv1`, so without this
 * switch the services silently drop the extension *and its per-request CPU cost*.
 *
 * Opt-in per service: `misarch.graphql.legacy-federated-tracing: true` (application.yaml). The
 * [FederatedTracingInstrumentation] bean of graphql-kotlin (`graphql.federation.tracing.enabled`, default true) is
 * then replaced by one with the federation-jvm 4.1.0 predicate. Off in every service (docs/PORTING.md §7); only for
 * experiments that reproduce the original Kotlin services' overhead.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["misarch.graphql.legacy-federated-tracing"], havingValue = "true")
class LegacyFederatedTracingConfiguration {

    companion object {
        /** GraphQL context key / HTTP header of Apollo's trace opt-in. */
        const val TRACE_HEADER = "apollo-federation-include-trace"

        /** federation-jvm 4.1.0 `Options.shouldTrace` without predicate: trace unless the request opts out. */
        fun legacyShouldTrace(input: ExecutionInput?): Boolean {
            val context = input?.graphQLContext ?: return true
            return if (context.hasKey(TRACE_HEADER)) "ftv1" == context.get<Any>(TRACE_HEADER) else true
        }

        @Bean
        @JvmStatic
        fun legacyFederatedTracingPostProcessor(environment: Environment): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is FederatedTracingInstrumentation) {
                    val debug = environment.getProperty("graphql.federation.tracing.debug", Boolean::class.java, false)
                    FederatedTracingInstrumentation(FederatedTracingInstrumentation.Options(debug, ::legacyShouldTrace))
                } else {
                    bean
                }
        }
    }
}
