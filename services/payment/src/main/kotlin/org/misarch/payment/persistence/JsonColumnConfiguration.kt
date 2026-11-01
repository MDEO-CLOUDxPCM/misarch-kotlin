package org.misarch.payment.persistence

import io.r2dbc.postgresql.codec.Json
import org.misarch.payment.event.model.OrderDTO
import org.misarch.payment.persistence.model.CreditCardDetails
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.TypeDescriptor
import org.springframework.core.convert.converter.GenericConverter
import org.springframework.core.convert.converter.GenericConverter.ConvertiblePair
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.dialect.PostgresDialect
import tools.jackson.databind.json.JsonMapper

/**
 * Columns that hold a value object as a JSON document ([OrderDTO], [CreditCardDetails]), written and read with the
 * application's [JsonMapper].
 */
@Configuration(proxyBeanMethods = false)
class JsonColumnConfiguration {

    @Bean
    fun r2dbcCustomConversions(jsonMapper: JsonMapper): R2dbcCustomConversions {
        val types = setOf(OrderDTO::class.java, CreditCardDetails::class.java)
        return R2dbcCustomConversions.of(PostgresDialect.INSTANCE, listOf(JsonWriter(jsonMapper, types), JsonReader(jsonMapper, types)))
    }

    @WritingConverter
    private class JsonWriter(private val jsonMapper: JsonMapper, private val types: Set<Class<*>>) : GenericConverter {
        override fun getConvertibleTypes() = types.map { ConvertiblePair(it, Json::class.java) }.toSet()

        override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? =
            source?.let { Json.of(jsonMapper.writeValueAsBytes(it)) }
    }

    @ReadingConverter
    private class JsonReader(private val jsonMapper: JsonMapper, private val types: Set<Class<*>>) : GenericConverter {
        override fun getConvertibleTypes() = types.map { ConvertiblePair(Json::class.java, it) }.toSet()

        override fun convert(source: Any?, sourceType: TypeDescriptor, targetType: TypeDescriptor): Any? =
            (source as Json?)?.let { jsonMapper.readValue(it.asArray(), targetType.type) }
    }

}
