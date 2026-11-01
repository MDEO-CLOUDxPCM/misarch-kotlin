-- Payment amounts are integers in the smallest currency unit (cents), like every other amount of the system and the
-- `compensatableOrderAmount` of the order events the payments are created from.
ALTER TABLE PaymentEntity ALTER COLUMN totalAmount TYPE BIGINT USING round(totalAmount)::BIGINT;
