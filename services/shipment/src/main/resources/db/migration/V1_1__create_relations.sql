CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- a user address has a userId, a vendor address has none; the current vendor address has the highest version
CREATE TABLE AddressEntity (
    id UUID PRIMARY KEY,
    userId UUID,
    street1 TEXT NOT NULL,
    street2 TEXT NOT NULL,
    city TEXT NOT NULL,
    postalCode TEXT NOT NULL,
    country TEXT NOT NULL,
    companyName TEXT,
    version BIGSERIAL
);
CREATE INDEX addressEntity_vendor_version ON AddressEntity (version DESC) WHERE userId IS NULL;

CREATE TABLE ProductVariantVersionEntity (
    id UUID PRIMARY KEY,
    weight DOUBLE PRECISION NOT NULL
);

CREATE TABLE ShipmentMethodEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    externalReference TEXT NOT NULL,
    baseFees INTEGER NOT NULL,
    feesPerItem INTEGER NOT NULL,
    feesPerKg INTEGER NOT NULL,
    archivedAt TIMESTAMPTZ
);

CREATE TABLE ShipmentEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    status TEXT NOT NULL,
    shipmentMethodId UUID NOT NULL,
    shipmentAddressId UUID NOT NULL,
    orderId UUID,
    returnId UUID,
    FOREIGN KEY (shipmentMethodId) REFERENCES ShipmentMethodEntity(id),
    FOREIGN KEY (shipmentAddressId) REFERENCES AddressEntity(id)
);
CREATE INDEX shipmentEntity_orderId ON ShipmentEntity (orderId);
CREATE INDEX shipmentEntity_returnId ON ShipmentEntity (returnId);

CREATE TABLE OrderItemEntity (
    id UUID PRIMARY KEY,
    sentWithId UUID NOT NULL,
    quantity INTEGER NOT NULL,
    productVariantVersionId UUID NOT NULL,
    FOREIGN KEY (sentWithId) REFERENCES ShipmentEntity(id)
);

CREATE TABLE ShipmentToOrderItemEntity (
    shipmentId UUID NOT NULL,
    orderItemId UUID NOT NULL,
    PRIMARY KEY (shipmentId, orderItemId),
    FOREIGN KEY (shipmentId) REFERENCES ShipmentEntity(id),
    FOREIGN KEY (orderItemId) REFERENCES OrderItemEntity(id)
);
