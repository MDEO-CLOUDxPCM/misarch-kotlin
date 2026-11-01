CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE OrderEntity (
    id UUID PRIMARY KEY,
    userId UUID NOT NULL
);
CREATE INDEX orderEntity_userId ON OrderEntity (userId);

CREATE TABLE ReturnEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reason TEXT NOT NULL,
    orderId UUID NOT NULL,
    refundedAmount BIGINT NOT NULL,
    createdAt TIMESTAMPTZ NOT NULL,
    FOREIGN KEY (orderId) REFERENCES OrderEntity(id)
);
CREATE INDEX returnEntity_orderId ON ReturnEntity (orderId);

CREATE TABLE OrderItemEntity (
    id UUID PRIMARY KEY,
    returnedWithId UUID,
    sentWithId UUID,
    orderId UUID NOT NULL,
    compensatableAmount BIGINT,
    productVariantVersionId UUID,
    FOREIGN KEY (orderId) REFERENCES OrderEntity(id)
);
CREATE INDEX orderItemEntity_returnedWithId ON OrderItemEntity (returnedWithId);

CREATE TABLE ProductVariantVersionEntity (
    id UUID PRIMARY KEY,
    canBeReturnedForDays INTEGER
);

CREATE TABLE ShipmentEntity (
    id UUID PRIMARY KEY,
    deliveredAt TIMESTAMPTZ
);
