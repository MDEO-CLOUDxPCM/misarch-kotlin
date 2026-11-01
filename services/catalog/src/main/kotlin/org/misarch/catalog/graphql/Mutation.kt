package org.misarch.catalog.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.input.CreateCategoricalCategoryCharacteristicInput
import org.misarch.catalog.graphql.input.CreateCategoryInput
import org.misarch.catalog.graphql.input.CreateNumericalCategoryCharacteristicInput
import org.misarch.catalog.graphql.input.CreateProductInput
import org.misarch.catalog.graphql.input.CreateProductVariantInput
import org.misarch.catalog.graphql.input.CreateProductVariantVersionInput
import org.misarch.catalog.graphql.input.UpdateProductInput
import org.misarch.catalog.graphql.input.UpdateProductVariantInput
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.catalog.graphql.model.Category
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristic
import org.misarch.catalog.graphql.model.Product
import org.misarch.catalog.graphql.model.ProductVariant
import org.misarch.catalog.graphql.model.ProductVariantVersion
import org.misarch.catalog.service.CategoryCharacteristicService
import org.misarch.catalog.service.CategoryService
import org.misarch.catalog.service.ProductService
import org.misarch.catalog.service.ProductVariantService
import org.misarch.catalog.service.ProductVariantVersionService
import org.misarch.common.graphql.authorizedUser
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 */
@Component
class Mutation(
    private val productService: ProductService,
    private val productVariantService: ProductVariantService,
    private val productVariantVersionService: ProductVariantVersionService,
    private val categoryService: CategoryService,
    private val categoryCharacteristicService: CategoryCharacteristicService
) : Mutation {

    @GraphQLDescription("Create a new product")
    suspend fun createProduct(
        @GraphQLDescription("Input for the createProduct mutation")
        input: CreateProductInput,
        dfe: DataFetchingEnvironment
    ): Product {
        dfe.authorizedUser.checkIsEmployee()
        val product = productService.createProduct(input)
        return product.toDTO()
    }

    @GraphQLDescription("Create a new product variant")
    suspend fun createProductVariant(
        @GraphQLDescription("Input for the createProductVariant mutation")
        input: CreateProductVariantInput,
        dfe: DataFetchingEnvironment
    ): ProductVariant {
        dfe.authorizedUser.checkIsEmployee()
        val productVariant = productVariantService.createProductVariant(input)
        return productVariant.toDTO()
    }

    @GraphQLDescription("Create a new product variant version")
    suspend fun createProductVariantVersion(
        @GraphQLDescription("Input for the createProductVariantVersion mutation")
        input: CreateProductVariantVersionInput,
        dfe: DataFetchingEnvironment
    ): ProductVariantVersion {
        dfe.authorizedUser.checkIsEmployee()
        val product = productVariantVersionService.createProductVariantVersion(input)
        return product.toDTO()
    }

    @GraphQLDescription("Create a new category")
    suspend fun createCategory(
        @GraphQLDescription("Input for the createCategory mutation")
        input: CreateCategoryInput,
        dfe: DataFetchingEnvironment
    ): Category {
        dfe.authorizedUser.checkIsEmployee()
        val category = categoryService.createCategory(input)
        return category.toDTO()
    }

    @GraphQLDescription("Create a new value for a characteristic whose values have no further meaning")
    suspend fun createCategoricalCategoryCharacteristic(
        @GraphQLDescription("Input for the createCategoricalCategoryCharacteristic mutation")
        input: CreateCategoricalCategoryCharacteristicInput,
        dfe: DataFetchingEnvironment
    ): CategoricalCategoryCharacteristic {
        dfe.authorizedUser.checkIsEmployee()
        val categoricalCategoryCharacteristic =
            categoryCharacteristicService.createCategoricalCategoryCharacteristic(input)
        return categoricalCategoryCharacteristic.toDTO() as CategoricalCategoryCharacteristic
    }

    @GraphQLDescription("Create a new numerical category characteristic")
    suspend fun createNumericalCategoryCharacteristic(
        @GraphQLDescription("Input for the createNumericalCategoryCharacteristic mutation")
        input: CreateNumericalCategoryCharacteristicInput,
        dfe: DataFetchingEnvironment
    ): NumericalCategoryCharacteristic {
        dfe.authorizedUser.checkIsEmployee()
        val numericalCategoryCharacteristic = categoryCharacteristicService.createNumericalCategoryCharacteristic(input)
        return numericalCategoryCharacteristic.toDTO() as NumericalCategoryCharacteristic
    }

    @GraphQLDescription("Update a product")
    suspend fun updateProduct(
        @GraphQLDescription("Input for the updateProduct mutation")
        input: UpdateProductInput,
        dfe: DataFetchingEnvironment
    ): Product {
        dfe.authorizedUser.checkIsEmployee()
        val product = productService.updateProduct(input)
        return product.toDTO()
    }

    @GraphQLDescription("Update a product variant")
    suspend fun updateProductVariant(
        @GraphQLDescription("Input for the updateProductVariant mutation")
        input: UpdateProductVariantInput,
        dfe: DataFetchingEnvironment
    ): ProductVariant {
        dfe.authorizedUser.checkIsEmployee()
        val productVariant = productVariantService.updateProductVariant(input)
        return productVariant.toDTO()
    }

}