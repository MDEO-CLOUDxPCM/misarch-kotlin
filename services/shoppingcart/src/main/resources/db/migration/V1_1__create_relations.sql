CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE ProductVariantEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE UserEntity (
    id UUID PRIMARY KEY,
    shoppingcartLastUpdatedAt TIMESTAMPTZ NOT NULL
);

-- no foreign key on productVariantId: a cart may hold a product variant this service has not received
CREATE TABLE ShoppingCartItemEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    userId UUID NOT NULL,
    count BIGINT NOT NULL,
    addedAt TIMESTAMPTZ NOT NULL,
    productVariantId UUID NOT NULL,
    FOREIGN KEY (userId) REFERENCES UserEntity(id)
);
CREATE INDEX shoppingCartItemEntity_userId ON ShoppingCartItemEntity (userId);
