CREATE TABLE products (
    product_id uuid PRIMARY KEY, sku varchar(100) NOT NULL UNIQUE, name varchar(200) NOT NULL,
    on_hand bigint NOT NULL CHECK(on_hand BETWEEN 0 AND 1000000000),
    reserved bigint NOT NULL CHECK(reserved >= 0 AND reserved <= on_hand),
    version bigint NOT NULL CHECK(version > 0), updated_at timestamptz NOT NULL
);
CREATE TABLE stock_movements (
    movement_id uuid PRIMARY KEY, product_id uuid NOT NULL REFERENCES products(product_id),
    operation varchar(30) NOT NULL, delta_hand bigint NOT NULL, delta_reserved bigint NOT NULL,
    version bigint NOT NULL, response text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE reservations (
    order_id uuid PRIMARY KEY, state varchar(40) NOT NULL,
    last_order_version bigint NOT NULL, version bigint NOT NULL, items text NOT NULL
);
