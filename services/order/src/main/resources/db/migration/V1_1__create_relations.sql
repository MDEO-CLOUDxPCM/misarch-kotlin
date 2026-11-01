CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE UserAddressEntity (
    userId UUID NOT NULL,
    id UUID NOT NULL,
    PRIMARY KEY (userId, id),
    FOREIGN KEY (userId) REFERENCES UserEntity(id)
);

CREATE TABLE CouponEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ShipmentMethodEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductVariantEntity (
    id UUID PRIMARY KEY,
    currentVersionId UUID NOT NULL,
    currentVersionPrice BIGINT NOT NULL,
    currentVersionTaxRateId UUID NOT NULL,
    isPubliclyVisible BOOLEAN NOT NULL
);

CREATE TABLE TaxRateEntity (
    id UUID PRIMARY KEY,
    currentVersionId UUID NOT NULL,
    currentVersionRate DOUBLE PRECISION NOT NULL,
    currentVersionVersion BIGINT NOT NULL
);

CREATE TABLE OrderEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    userId UUID NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL,
    orderStatus TEXT NOT NULL,
    placedAt TIMESTAMPTZ,
    rejectionReason TEXT,
    shipmentAddressId UUID NOT NULL,
    invoiceAddressId UUID NOT NULL,
    compensatableOrderAmount BIGINT NOT NULL,
    paymentInformationId UUID NOT NULL,
    vatNumber TEXT
);
CREATE INDEX orderEntity_userId ON OrderEntity (userId);

CREATE TABLE OrderItemEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    orderId UUID NOT NULL,
    position INTEGER NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL,
    productVariantId UUID NOT NULL,
    productVariantIsPubliclyVisible BOOLEAN NOT NULL,
    productVariantVersionId UUID NOT NULL,
    productVariantVersionPrice BIGINT NOT NULL,
    productVariantVersionTaxRateId UUID NOT NULL,
    taxRateVersionId UUID NOT NULL,
    taxRateVersionRate DOUBLE PRECISION NOT NULL,
    taxRateVersionVersion BIGINT NOT NULL,
    shoppingCartItemId UUID NOT NULL,
    count BIGINT NOT NULL,
    compensatableAmount BIGINT NOT NULL,
    shipmentMethodId UUID NOT NULL,
    FOREIGN KEY (orderId) REFERENCES OrderEntity(id)
);
CREATE INDEX orderItemEntity_orderId ON OrderItemEntity (orderId);

CREATE TABLE OrderItemToDiscountEntity (
    orderItemId UUID NOT NULL,
    discountId UUID NOT NULL,
    discount DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (orderItemId, discountId),
    FOREIGN KEY (orderItemId) REFERENCES OrderItemEntity(id)
);

CREATE TABLE OrderCompensationEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    orderId UUID NOT NULL,
    triggeredAt TIMESTAMPTZ NOT NULL,
    amountToCompensate BIGINT NOT NULL
);
CREATE INDEX orderCompensationEntity_orderId ON OrderCompensationEntity (orderId);

CREATE TABLE OrderCompensationToOrderItemEntity (
    orderCompensationId UUID NOT NULL,
    orderItemId UUID NOT NULL,
    position INTEGER NOT NULL,
    PRIMARY KEY (orderCompensationId, orderItemId),
    FOREIGN KEY (orderCompensationId) REFERENCES OrderCompensationEntity(id)
);
