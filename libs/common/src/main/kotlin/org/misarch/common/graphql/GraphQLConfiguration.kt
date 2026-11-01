package org.misarch.common.graphql

import com.expediagroup.graphql.generator.federation.FederatedSchemaGeneratorHooks
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.federation.directives.FEDERATION_SPEC
import com.expediagroup.graphql.generator.federation.directives.LinkDirective
import com.expediagroup.graphql.generator.federation.execution.FederatedTypeResolver
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLContextFactory
import com.expediagroup.graphql.server.spring.execution.SpringGraphQLRequestParser
import graphql.execution.DataFetcherExceptionHandler
import graphql.scalars.datetime.DateScalar
import graphql.scalars.datetime.DateTimeScalar
import graphql.scalars.id.UUIDScalar
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLType
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Optional
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType

/**
 * GraphQL setup shared by all services (federation is switched on by the defaults in
 * [org.misarch.common.config.MisarchDefaultsEnvironmentPostProcessor]):
 * - federated schema hooks with the MiSArch scalars ([ScalarMapping]),
 * - directive definitions cleared like in the original services (`didBuildSchema` -> `clearDirectives()`),
 *   the federation printer adds the used federation directives again,
 * - federation spec pinned to v2.5 ([FEDERATION_URL]),
 * - argument default values from [GraphQLDefaultValue],
 * - exception handling: graphql-java default, plain messages for [GraphQLErrorException],
 * - the `Authorized-User` context factory,
 * - GraphQL multipart requests ([Upload]) if `misarch.graphql.multipart-upload: true`.
 */
@Configuration(proxyBeanMethods = false)
class GraphQLConfiguration {

    @Bean
    @ConditionalOnMissingBean(FederatedSchemaGeneratorHooks::class)
    fun federatedSchemaGeneratorHooks(
        resolvers: Optional<List<FederatedTypeResolver>>,
        additionalScalars: Optional<List<ScalarMapping>>
    ): FederatedSchemaGeneratorHooks {
        val scalars: Map<KClass<*>, GraphQLType> = DEFAULT_SCALARS
            .plus(additionalScalars.orElse(emptyList()).map { it })
            .associate { it.kClass to it.scalar }
        return object : FederatedSchemaGeneratorHooks(resolvers.orElse(emptyList())) {
            override fun willBuildSchema(
                queries: List<TopLevelObject>,
                mutations: List<TopLevelObject>,
                subscriptions: List<TopLevelObject>,
                additionalTypes: Set<KType>,
                additionalInputTypes: Set<KType>,
                schemaObject: TopLevelObject?
            ): GraphQLSchema.Builder {
                // pin the federation spec version (graphql-kotlin 11 defaults to the latest, v2.13); same default
                // imports as graphql-kotlin, the printed @link lists only the used ones (like the originals)
                if (schemaObject?.kClass?.annotations?.none { it is LinkDirective } != false) {
                    linkSpecs.putIfAbsent(FEDERATION_SPEC, LinkSpec(FEDERATION_SPEC, FEDERATION_IMPORTS, FEDERATION_URL))
                }
                return super.willBuildSchema(queries, mutations, subscriptions, additionalTypes, additionalInputTypes, schemaObject)
            }

            override fun willGenerateGraphQLType(type: KType): GraphQLType? {
                return scalars[type.classifier] ?: super.willGenerateGraphQLType(type)
            }

            override fun didGenerateQueryField(
                kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition
            ): GraphQLFieldDefinition =
                super.didGenerateQueryField(kClass, function, GraphQLDefaultValues.apply(function, fieldDefinition))

            override fun didGenerateMutationField(
                kClass: KClass<*>, function: KFunction<*>, fieldDefinition: GraphQLFieldDefinition
            ): GraphQLFieldDefinition =
                super.didGenerateMutationField(kClass, function, GraphQLDefaultValues.apply(function, fieldDefinition))

            override fun willAddGraphQLTypeToSchema(type: KType, generatedType: GraphQLType): GraphQLType {
                val kClass = type.classifier as? KClass<*>
                val withDefaults = if (kClass != null) GraphQLDefaultValues.apply(kClass, generatedType) else generatedType
                return super.willAddGraphQLTypeToSchema(type, withDefaults)
            }

            override fun didBuildSchema(builder: GraphQLSchema.Builder): GraphQLSchema.Builder {
                return super.didBuildSchema(builder).clearDirectives()
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(DataFetcherExceptionHandler::class)
    fun misarchDataFetcherExceptionHandler(): DataFetcherExceptionHandler = MisarchDataFetcherExceptionHandler()

    @Bean
    @ConditionalOnMissingBean(SpringGraphQLContextFactory::class)
    fun authorizedUserGraphQLContextFactory(objectMapper: ObjectMapper): SpringGraphQLContextFactory =
        AuthorizedUserGraphQLContextFactory(objectMapper)

    @Bean
    @ConditionalOnProperty(name = ["misarch.graphql.multipart-upload"], havingValue = "true")
    @ConditionalOnMissingBean(SpringGraphQLRequestParser::class)
    fun multipartGraphQLRequestParser(objectMapper: ObjectMapper): SpringGraphQLRequestParser =
        MultipartGraphQLRequestParser(objectMapper)

    companion object {
        /**
         * Apollo Federation spec version of all original MiSArch subgraphs and of the gateway's supergraph
         * (`federation_version: =2.5.0`).
         */
        const val FEDERATION_URL = "https://specs.apollo.dev/federation/v2.5"

        /** graphql-kotlin's default (un-namespaced) federation imports. */
        val FEDERATION_IMPORTS: Map<String, String> = listOf(
            "composeDirective", "extends", "external", "inaccessible", "interfaceObject", "key", "override",
            "provides", "requires", "shareable", "tag", "FieldSet"
        ).associateWith { it }

        /** Scalars used by (almost) every MiSArch subgraph. */
        val DEFAULT_SCALARS: List<ScalarMapping> = listOf(
            ScalarMapping(UUID::class, UUIDScalar.INSTANCE),
            // without @specifiedBy, like extended-scalars 21 used by the original services
            ScalarMapping(OffsetDateTime::class, DateTimeScalar.INSTANCE.transform { it.specifiedByUrl(null) }),
            ScalarMapping(LocalDate::class, DateScalar.INSTANCE),
            // input-only, appears in the SDL only where an argument has the type Upload
            ScalarMapping(Upload::class, Upload.SCALAR),
        )
    }
}
