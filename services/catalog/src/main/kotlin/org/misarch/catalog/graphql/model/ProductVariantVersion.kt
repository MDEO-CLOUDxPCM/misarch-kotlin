package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.ProductVariantDataLoader
import org.misarch.catalog.graphql.dataloader.TaxRateDataLoader
import org.misarch.catalog.graphql.model.connection.CategoryCharacteristicValueConnection
import org.misarch.catalog.graphql.model.connection.CategoryCharacteristicValueOrder
import org.misarch.catalog.graphql.model.connection.MediaConnection
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.authorizedUserOrNull
import org.misarch.common.graphql.connection.CommonOrder
import org.misarch.common.graphql.load
import org.misarch.common.persistence.Condition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A version of a ProductVariant.")
@KeyDirective(fields = FieldSet("id"))
class ProductVariantVersion(
    id: UUID,
    @property:GraphQLDescription("The name of the ProductVariantVersion.")
    val name: String,
    @property:GraphQLDescription("The description of the ProductVariantVersion.")
    val description: String,
    @property:GraphQLDescription("The version of the ProductVariantVersion.")
    val version: Int,
    @property:GraphQLDescription("The retail price of the ProductVariantVersion.")
    val retailPrice: Int,
    @property:GraphQLDescription("The date when the ProductVariantVersion version was created.")
    val createdAt: OffsetDateTime,
    @property:GraphQLDescription("The amount of days for which an instance of the ProductVariantVersion can be returned after purchase, if null can be returned indefinitely.")
    val canBeReturnedForDays: Int?,
    @property:GraphQLDescription("The weight of the ProductVariantVersion.")
    val weight: Double,
    private val productVariantId: UUID,
    private val taxRateId: UUID
) : Node(id) {

    @GraphQLDescription("The ProductVariant this is a version of.")
    fun productVariant(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<ProductVariant> {
        return dfe.load(ProductVariantDataLoader::class, productVariantId)
    }

    @GraphQLDescription("Get all associated CategoryCharacteristicValues")
    suspend fun characteristicValues(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CategoryCharacteristicValueOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): CategoryCharacteristicValueConnection {
        return CategoryCharacteristicValueConnection(
            first,
            skip,
            Condition.eq("t.productVariantVersionId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull
        )
    }

    @GraphQLDescription("The associated TaxRate.")
    fun taxRate(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<TaxRate> {
        return dfe.load(TaxRateDataLoader::class, taxRateId)
    }

    @GraphQLDescription("Get all associated Medias")
    suspend fun medias(
        @GraphQLDescription("Number of items to return")
        first: Int? = null,
        @GraphQLDescription("Number of items to skip")
        skip: Int? = null,
        @GraphQLDescription("Ordering")
        orderBy: CommonOrder? = null,
        @GraphQLIgnore
        @Autowired
        entityTemplate: R2dbcEntityTemplate,
        dfe: DataFetchingEnvironment
    ): MediaConnection {
        return MediaConnection(
            first,
            skip,
            Condition.eq("j.productVariantVersionId", id),
            orderBy,
            entityTemplate,
            dfe.authorizedUserOrNull,
            "INNER JOIN ProductVariantVersionToMediaEntity j ON j.mediaId = t.id"
        )
    }

}
