CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE PaymentInformationEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    paymentMethod TEXT NOT NULL,
    publicMethodDetails JSONB,
    secretMethodDetails JSONB,
    userId UUID NOT NULL
);
CREATE INDEX paymentInformationEntity_userId ON PaymentInformationEntity (userId);
CREATE INDEX paymentInformationEntity_paymentMethod ON PaymentInformationEntity (paymentMethod);

CREATE TABLE PaymentEntity (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    totalAmount BIGINT NOT NULL,
    status TEXT NOT NULL,
    paymentInformationId UUID NOT NULL,
    payedAt TIMESTAMPTZ,
    numberOfRetries INTEGER NOT NULL DEFAULT 0,
    createdAt TIMESTAMPTZ NOT NULL,
    updatedAt TIMESTAMPTZ NOT NULL
);
CREATE INDEX paymentEntity_paymentInformationId ON PaymentEntity (paymentInformationId);
CREATE INDEX paymentEntity_createdAt ON PaymentEntity (createdAt);
CREATE INDEX paymentEntity_status_createdAt ON PaymentEntity (status, createdAt);

CREATE TABLE OpenOrderEntity (
    paymentId UUID PRIMARY KEY,
    orderData JSONB NOT NULL,
    orderId UUID,
    UNIQUE (orderId)
);
