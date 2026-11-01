package org.misarch.common.graphql

import com.apollographql.federation.graphqljava.tracing.FederatedTracingInstrumentation
import graphql.ExecutionInput
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/**
 * Apollo federated tracing (`extensions.ftv1`, a base64 protobuf trace with per-field timings) for every GraphQL
 * request that does not opt out.
 *
 * federation-jvm traces a request only if its GraphQL context has the entry `apollo-federation-include-trace: ftv1`;
 * the MiSArch gateway never sends it, so by default no response carries a trace. With
 * `misarch.graphql.federated-tracing: true` (application.yaml) the [FederatedTracingInstrumentation] bean of
 * graphql-kotlin (`graphql.federation.tracing.enabled`, default true) is replaced by one that traces every request
 * unless the entry has another value. Off in every service (docs/PORTING.md §7); it exists for experiments that
 * measure the cost of tracing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["misarch.graphql.federated-tracing"], havingValue = "true")
class FederatedTracingConfiguration {

    companion object {
        /** GraphQL context key / HTTP header of Apollo's trace opt-in. */
        const val TRACE_HEADER = "apollo-federation-include-trace"

        /** Trace unless the request opts out (an [TRACE_HEADER] entry other than `ftv1`). */
        fun traceUnlessOptedOut(input: ExecutionInput?): Boolean {
            val context = input?.graphQLContext ?: return true
            return if (context.hasKey(TRACE_HEADER)) "ftv1" == context.get<Any>(TRACE_HEADER) else true
        }

        @Bean
        @JvmStatic
        fun federatedTracingPostProcessor(environment: Environment): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is FederatedTracingInstrumentation) {
                    val debug = environment.getProperty("graphql.federation.tracing.debug", Boolean::class.java, false)
                    FederatedTracingInstrumentation(FederatedTracingInstrumentation.Options(debug, ::traceUnlessOptedOut))
                } else {
                    bean
                }
        }
    }
}
