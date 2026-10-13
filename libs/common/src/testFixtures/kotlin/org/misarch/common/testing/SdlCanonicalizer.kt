package org.misarch.common.testing

import graphql.language.AstPrinter
import graphql.language.Description
import graphql.language.Directive
import graphql.language.Document
import graphql.language.EnumTypeDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.InputValueDefinition
import graphql.language.InterfaceTypeDefinition
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.TypeDefinition
import graphql.language.UnionTypeDefinition
import graphql.parser.Parser
import graphql.parser.ParserEnvironment
import graphql.parser.ParserOptions

/**
 * Canonical, order-independent text form of a subgraph SDL, for semantic comparison (tools/sdl-diff.sh).
 *
 * Kept: every type (kind, name, implemented interfaces, union members, description), every field / input field /
 * argument / enum value (name, type, default value, description) and every applied directive with its arguments
 * (e.g. `@key(fields: "id")`, `@shareable`, `@external`, `@deprecated`).
 * Normalized: ordering (types, fields, arguments, values, interfaces, directives), whitespace in descriptions,
 * `extend type X` merged into `type X`, `@key(resolvable: true)` (the default) dropped.
 * Ignored (federation/library boilerplate that differs between graphql-kotlin, async-graphql and @apollo/subgraph):
 * the schema definition and its `@link`, all directive definitions, the federation types (`_Any`, `_Entity`,
 * `_Service`, `FieldSet`, `link__*`, `federation__*`), `Query._service` / `Query._entities`, the applied
 * directives `@link`, `@specifiedBy`, `@extends`, `@composeDirective`.
 */
object SdlCanonicalizer {

    private val IGNORED_TYPES = setOf("_Any", "_Entity", "_Service", "FieldSet", "_FieldSet", "link__Import", "link__Purpose")
    private val IGNORED_TYPE_PREFIXES = listOf("link__", "federation__")
    private val IGNORED_QUERY_FIELDS = setOf("_service", "_entities")
    private val IGNORED_DIRECTIVES = setOf("link", "specifiedBy", "extends", "composeDirective")

    fun canonicalize(sdl: String, includeDescriptions: Boolean = true): String {
        val document = parse(sdl)
        val types = document.definitions.filterIsInstance<TypeDefinition<*>>()
            .filterNot { isIgnoredType(it.name) }
            .groupBy { it.name }
        val out = StringBuilder()
        for (name in types.keys.sorted()) {
            out.append(renderType(types.getValue(name), includeDescriptions)).append('\n')
        }
        return out.toString()
    }

    private fun parse(sdl: String): Document = Parser().parseDocument(
        ParserEnvironment.newParserEnvironment()
            .document(sdl)
            .parserOptions(ParserOptions.getDefaultSdlParserOptions())
            .build()
    )

    private fun isIgnoredType(name: String) = name in IGNORED_TYPES || IGNORED_TYPE_PREFIXES.any { name.startsWith(it) }

    private fun renderType(defs: List<TypeDefinition<*>>, withDesc: Boolean): String {
        val first = defs.first()
        val name = first.name
        val kind = when (first) {
            is InterfaceTypeDefinition -> "interface"
            is ObjectTypeDefinition -> "type"
            is InputObjectTypeDefinition -> "input"
            is EnumTypeDefinition -> "enum"
            is UnionTypeDefinition -> "union"
            is ScalarTypeDefinition -> "scalar"
            else -> first.javaClass.simpleName
        }
        val description = defs.firstNotNullOfOrNull { descriptionOf(it) }
        val directives = defs.flatMap { it.directives }
        val lines = mutableListOf<String>()
        val header = StringBuilder("$kind $name")
        when (first) {
            is ObjectTypeDefinition -> {
                val interfaces = defs.flatMap { (it as ObjectTypeDefinition).implements }.map { AstPrinter.printAst(it) }.toSortedSet()
                if (interfaces.isNotEmpty()) header.append(" implements ").append(interfaces.joinToString(" & "))
                val fields = defs.flatMap { (it as ObjectTypeDefinition).fieldDefinitions }
                    .filterNot { name == "Query" && it.name in IGNORED_QUERY_FIELDS }
                lines += fields.map { renderField(it, withDesc) }
            }
            is InterfaceTypeDefinition -> {
                val interfaces = defs.flatMap { (it as InterfaceTypeDefinition).implements }.map { AstPrinter.printAst(it) }.toSortedSet()
                if (interfaces.isNotEmpty()) header.append(" implements ").append(interfaces.joinToString(" & "))
                lines += defs.flatMap { (it as InterfaceTypeDefinition).fieldDefinitions }.map { renderField(it, withDesc) }
            }
            is InputObjectTypeDefinition ->
                lines += defs.flatMap { (it as InputObjectTypeDefinition).inputValueDefinitions }.map { renderInputValue(it, withDesc) }
            is EnumTypeDefinition ->
                lines += defs.flatMap { (it as EnumTypeDefinition).enumValueDefinitions }.map { value ->
                    value.name + renderDirectives(value.directives) + desc(descriptionOf(value), withDesc)
                }
            is UnionTypeDefinition -> {
                val members = defs.flatMap { (it as UnionTypeDefinition).memberTypes }.map { AstPrinter.printAst(it) }.toSortedSet()
                header.append(" = ").append(members.joinToString(" | "))
            }
        }
        header.append(renderDirectives(directives)).append(desc(description, withDesc))
        return (listOf(header.toString()) + lines.distinct().sorted().map { "  $it" }).joinToString("\n")
    }

    private fun renderField(field: FieldDefinition, withDesc: Boolean): String {
        val args = field.inputValueDefinitions.sortedBy { it.name }.joinToString(", ") { renderInputValue(it, withDesc) }
        val argList = if (args.isEmpty()) "" else "($args)"
        return field.name + argList + ": " + AstPrinter.printAst(field.type) +
            renderDirectives(field.directives) + desc(descriptionOf(field), withDesc)
    }

    private fun renderInputValue(value: InputValueDefinition, withDesc: Boolean): String {
        val default = value.defaultValue?.let { " = " + AstPrinter.printAst(it) } ?: ""
        return value.name + ": " + AstPrinter.printAst(value.type) + default +
            renderDirectives(value.directives) + desc(descriptionOf(value), withDesc)
    }

    private fun renderDirectives(directives: List<Directive>): String = directives
        .filterNot { it.name in IGNORED_DIRECTIVES || it.name.startsWith("federation__") }
        .map { directive ->
            val args = directive.arguments
                .filterNot { directive.name == "key" && it.name == "resolvable" && AstPrinter.printAst(it.value) == "true" }
                .sortedBy { it.name }
                .joinToString(", ") { "${it.name}: ${AstPrinter.printAst(it.value)}" }
            "@" + directive.name + if (args.isEmpty()) "" else "($args)"
        }
        .sorted()
        .joinToString("") { " $it" }

    private fun descriptionOf(node: Any): String? = when (node) {
        is TypeDefinition<*> -> (node as? graphql.language.AbstractDescribedNode<*>)?.description
        is graphql.language.AbstractDescribedNode<*> -> node.description
        else -> null
    }?.let { normalize(it) }

    private fun normalize(description: Description): String? =
        description.content?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

    private fun desc(text: String?, withDesc: Boolean): String = if (withDesc && text != null) "  # \"$text\"" else ""
}
