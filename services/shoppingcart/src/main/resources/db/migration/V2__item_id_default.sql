-- The database generates the ids of new shopping cart items (like the ids of the other services' tables), so the
-- service saves a new ShoppingCartItemEntity with a null id. Existing rows are unchanged.
ALTER TABLE ShoppingCartItemEntity ALTER COLUMN id SET DEFAULT gen_random_uuid();
