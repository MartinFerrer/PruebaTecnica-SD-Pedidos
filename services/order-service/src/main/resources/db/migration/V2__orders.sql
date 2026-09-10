CREATE TABLE orders (
    order_id uuid PRIMARY KEY, status varchar(255) NOT NULL CHECK(status IN ('PENDING','CONFIRMED','REJECTED','CANCELLED')),
    version bigint NOT NULL, inventory_cancellation_status varchar(255), cancellation_version bigint NOT NULL DEFAULT 0,
    unavailable_items text NOT NULL
);
CREATE TABLE order_items (
    order_id uuid NOT NULL REFERENCES orders(order_id), product_id uuid NOT NULL,
    quantity bigint NOT NULL CHECK(quantity BETWEEN 1 AND 1000000000), PRIMARY KEY(order_id,product_id)
);
