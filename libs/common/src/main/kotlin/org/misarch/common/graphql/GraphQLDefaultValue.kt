package org.misarch.common.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLName
import graphql.parser.Parser
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberFunctions

/**
 * Declares the default value of a GraphQL argument in the schema (graphql-kotlin ignores Kotlin default values),
 * e.g. for the NestJS originals' `skip: Int = 0, first: Int = 2147483647`:
 * ```
 * fun productItems(
 *     @GraphQLDefaultValue("0") skip: Int? = 0,
 *     @GraphQLDefaultValue("2147483647") first: Int? = Int.MAX_VALUE,
 * ): ProductItemConnection
 * ```
 * [literal] is a GraphQL value literal (`0`, `"text"`, `ASC`, `[1, 2]`, `{field: ID}`). Works for Query/Mutation
 * fields and functions of object/interface types (applied by the hooks of [GraphQLConfiguration]).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class GraphQLDefaultValue(val literal: String)

internal object GraphQLDefaultValues {

    fun apply(function: KFunction<*>, field: GraphQLFieldDefinition): GraphQLFieldDefinition {
        val defaults = function.parameters.mapNotNull { parameter ->
            val annotation = parameter.findAnnotation<GraphQLDefaultValue>() ?: return@mapNotNull null
            val name = parameter.findAnnotation<GraphQLName>()?.value ?: parameter.name
            name?.let { it to annotation.literal }
        }.toMap()
        if (defaults.isEmpty()) return field
        return field.transform { builder ->
            builder.replaceArguments(field.arguments.map { argument ->
                val literal = defaults[argument.name]
                if (literal == null) argument else argument.transform { it.defaultValueLiteral(Parser.parseValue(literal)) }
            })
        }
    }

    fun apply(kClass: KClass<*>, type: GraphQLType): GraphQLType {
        val functions by lazy {
            kClass.memberFunctions.associateBy { it.findAnnotation<GraphQLName>()?.value ?: it.name }
        }
        fun transformField(field: GraphQLFieldDefinition): GraphQLFieldDefinition =
            functions[field.name]?.let { apply(it, field) } ?: field
        return when (type) {
            is GraphQLObjectType -> if (hasDefaults(kClass)) {
                type.transform { builder -> builder.replaceFields(type.fieldDefinitions.map(::transformField)) }
            } else type
            is GraphQLInterfaceType -> if (hasDefaults(kClass)) {
                type.transform { builder -> builder.replaceFields(type.fieldDefinitions.map(::transformField)) }
            } else type
            else -> type
        }
    }

    private fun hasDefaults(kClass: KClass<*>): Boolean = try {
        kClass.memberFunctions.any { f -> f.parameters.any { it.findAnnotation<GraphQLDefaultValue>() != null } }
    } catch (e: Throwable) {
        false
    }
}
