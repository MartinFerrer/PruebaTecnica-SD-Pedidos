"""Carga un conjunto de datos demo determinista e idempotente en local.

El ejecutor utiliza únicamente las APIs REST públicas de Order e Inventory,
espera las transiciones asíncronas de las órdenes y puede ejecutarse varias
veces sin duplicar productos, movimientos de stock ni órdenes.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request


INVENTORY_URL = os.getenv("INVENTORY_URL", "http://inventory-service:8080")
ORDER_URL = os.getenv("ORDER_URL", "http://order-service:8080")
TIMEOUT_SECONDS = int(os.getenv("DEMO_TIMEOUT_SECONDS", "45"))

PRODUCTS = (
    ("keyboard", "DEMO-KEYBOARD", "Mechanical Keyboard", 20),
    ("mouse", "DEMO-MOUSE", "Wireless Mouse", 50),
    ("monitor", "DEMO-MONITOR", "27-inch Monitor", 5),
    ("empty", "DEMO-EMPTY", "Out-of-stock Product", 0),
)
MISSING_PRODUCT_ID = "00000000-0000-4000-8000-000000000404"
RESTOCK_MOVEMENT_ID = "10000000-0000-4000-8000-000000000001"


def call(method, url, body=None, idempotency_key=None, expected=(200,)):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            status = response.status
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status = error.code
        payload = error.read().decode("utf-8")
    if status not in expected:
        raise RuntimeError(f"{method} {url} returned {status}: {payload}")
    return json.loads(payload) if payload else None


def idempotent_post(url, body, key, expected):
    first = call("POST", url, body, key, expected)
    replay = call("POST", url, body, key, expected)
    if first != replay:
        raise RuntimeError(f"Idempotent replay changed response for {key}")
    return first


def wait_order(order_id, status, cancellation_status=None):
    deadline = time.monotonic() + TIMEOUT_SECONDS
    while time.monotonic() < deadline:
        order = call("GET", f"{ORDER_URL}/orders/{order_id}")
        if order["status"] == status:
            cancellation_matches = (
                cancellation_status is None
                or order.get("inventoryCancellationStatus") == cancellation_status
            )
            if cancellation_matches:
                return order
        time.sleep(0.2)
    raise RuntimeError(
        f"Order {order_id} did not converge to {status}/{cancellation_status} within {TIMEOUT_SECONDS}s"
    )


def create_products():
    product_ids = {}
    for alias, sku, name, stock in PRODUCTS:
        result = idempotent_post(
            f"{INVENTORY_URL}/products",
            {"sku": sku, "name": name, "initialStock": stock},
            f"demo-v1-product-{alias}",
            (201,),
        )
        product_ids[alias] = result["productId"]
    return product_ids


def create_order(name, items):
    result = idempotent_post(
        f"{ORDER_URL}/orders",
        {"items": items},
        f"demo-v1-order-{name}",
        (202,),
    )
    return result["orderId"]


def main():
    product_ids = create_products()
    restock = idempotent_post(
        f"{INVENTORY_URL}/products/{product_ids['keyboard']}/restock",
        {"movementId": RESTOCK_MOVEMENT_ID, "quantity": 5, "reason": "DEMO_DELIVERY"},
        "demo-v1-restock-keyboard",
        (201,),
    )

    confirmed_id = create_order(
        "confirmed",
        [
            {"productId": product_ids["keyboard"], "quantity": 2},
            {"productId": product_ids["mouse"], "quantity": 1},
        ],
    )
    wait_order(confirmed_id, "CONFIRMED")

    rejected_id = create_order(
        "rejected",
        [
            {"productId": product_ids["empty"], "quantity": 1},
            {"productId": MISSING_PRODUCT_ID, "quantity": 1},
        ],
    )
    rejected = wait_order(rejected_id, "REJECTED")
    if len(rejected.get("unavailableItems", [])) != 2:
        raise RuntimeError("Rejected demo order did not report both unavailable items")

    cancelled_id = create_order(
        "cancelled",
        [{"productId": product_ids["monitor"], "quantity": 1}],
    )
    cancelled_order = call("GET", f"{ORDER_URL}/orders/{cancelled_id}")
    if cancelled_order["status"] != "CANCELLED":
        wait_order(cancelled_id, "CONFIRMED")
    idempotent_post(
        f"{ORDER_URL}/orders/{cancelled_id}/cancel",
        {"reason": "DEMO_CUSTOMER_REQUEST"},
        "demo-v1-cancel-order",
        (202,),
    )
    wait_order(cancelled_id, "CANCELLED", "COMPLETED")

    products = call("GET", f"{INVENTORY_URL}/products")
    orders = call("GET", f"{ORDER_URL}/orders")
    if not set(product_ids.values()).issubset({item["productId"] for item in products}):
        raise RuntimeError("One or more demo products are missing from GET /products")
    expected_orders = {confirmed_id, rejected_id, cancelled_id}
    if not expected_orders.issubset({item["orderId"] for item in orders}):
        raise RuntimeError("One or more demo orders are missing from GET /orders")

    print(
        json.dumps(
            {
                "products": product_ids,
                "orders": {
                    "confirmed": confirmed_id,
                    "rejected": rejected_id,
                    "cancelled": cancelled_id,
                },
                "restockMovementId": restock["movementId"],
                "result": "demo-data-ready",
            },
            indent=2,
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"demo-data failed: {error}", file=sys.stderr)
        sys.exit(1)
