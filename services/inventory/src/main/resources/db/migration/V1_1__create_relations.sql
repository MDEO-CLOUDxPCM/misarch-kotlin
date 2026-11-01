CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE ProductVariantEntity (
    id UUID PRIMARY KEY
);

CREATE TABLE ProductItemEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    productVariantId UUID NOT NULL,
    inventoryStatus TEXT NOT NULL,
    orderId UUID,
    -- a reservation takes the free items of a variant in the order they were added
    insertionOrder BIGSERIAL
);
CREATE INDEX productItemEntity_orderId ON ProductItemEntity (orderId);
CREATE INDEX productItemEntity_variant_status_insertionOrder ON ProductItemEntity (productVariantId, inventoryStatus, insertionOrder);
