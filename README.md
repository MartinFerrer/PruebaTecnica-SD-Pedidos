# Sistema distribuido de pedidos e inventario

Primera implementación funcional de Order e Inventory, con contratos REST/eventos, persistencia separada y coordinación asíncrona. La fase sigue siendo `IMPLEMENTACION`.

La arquitectura acordada usa Java 26, Spring Boot 4.1.1, arquitectura hexagonal, PostgreSQL, RabbitMQ, Docker Compose, pruebas automatizadas y CI/CD en GitHub.

## Documentación

- [Arquitectura del sistema](docs/architecture/ARCHITECTURE.md)
- [Flujos y contratos asíncronos](docs/architecture/EVENTS-AND-RACES.md)
- [Trazabilidad de requisitos y decisiones](docs/architecture/REQUIREMENTS-TRACEABILITY.md)
- [Estrategia de pruebas](docs/testing/TEST-STRATEGY.md)
- [Diseño de CI/CD](docs/delivery/CI-CD.md)
- [Roadmap de implementación y verificación](docs/delivery/IMPLEMENTATION-ROADMAP.md)
- [Observabilidad y telemetría](docs/operations/OBSERVABILITY.md)
- [Despliegue local y datos de demostración](docs/operations/LOCAL-DEPLOYMENT.md)
- [Reglas para agentes](AGENTS.md)
- [Guía de estilo de código](docs/development/CODE-STYLE.md)
- [Verificación y pendientes de implementación](docs/testing/IMPLEMENTATION-VERIFICATION.md)

## Ejecutar localmente

Para desplegar solo se requiere Docker Desktop/Engine con Compose v2. Copiar `.env.example` a `.env` y establecer contraseñas locales antes del primer arranque. El build instala Maven dentro de su etapa temporal, por lo que no depende de herramientas específicas del sistema anfitrión.
```text
docker compose up --build --wait --wait-timeout 180
```
- Order: `http://127.0.0.1:8081/orders`.
- Inventory: `http://127.0.0.1:8082/products`.
- RabbitMQ Management: `http://127.0.0.1:15672`, usuario `app`, contraseña configurada en `.env`.
- Readiness de cada API: `/actuator/health/readiness`.

Esta  tabla reúne todos los endpoints REST básicos; cada enlace lleva a la operación y al esquema de body correspondiente en
el contrato OpenAPI.

| Servicio y operación | Body | Headers y parámetros | Especificación detallada |
|---|---|---|---|
| `GET http://127.0.0.1:8082/products` | No lleva body. Lista todos los productos y su stock. | Ninguno. | [Inventory `GET /products`](contracts/openapi/inventory.json#L13) |
| `POST http://127.0.0.1:8082/products` | Requerido: `{"sku":"SKU-001","name":"Keyboard","initialStock":20}`. | `Idempotency-Key` requerido. | [Inventory `POST /products`](contracts/openapi/inventory.json#L33) |
| `PUT http://127.0.0.1:8082/products` | Requerido: `{"productId":"<UUID>","stock":25,"expectedVersion":1,"reason":"MANUAL_RECOUNT"}`. | `Idempotency-Key` requerido. | [Inventory `PUT /products`](contracts/openapi/inventory.json#L158) |
| `POST http://127.0.0.1:8082/products/{productId}/restock` | Requerido: `{"movementId":"<UUID>","quantity":5,"reason":"RECEIVING"}`. | `Idempotency-Key` y `productId` (UUID) requeridos. | [Inventory `POST /products/{productId}/restock`](contracts/openapi/inventory.json#L295) |
| `GET http://127.0.0.1:8082/products/{productId}/stock` | NA | `productId` (UUID) requerido. | [Inventory `GET /products/{productId}/stock`](contracts/openapi/inventory.json#L436) |
| `GET http://127.0.0.1:8081/orders` | NA. Lista todas las órdenes y su estado actual. | Ninguno. | [Order `GET /orders`](contracts/openapi/order.json#L13) |
| `POST http://127.0.0.1:8081/orders` | Requerido: `{"items":[{"productId":"<UUID>","quantity":2}]}`. | `Idempotency-Key` requerido. | [Order `POST /orders`](contracts/openapi/order.json#L33) |
| `GET http://127.0.0.1:8081/orders/{orderId}` | NA | `orderId` (UUID) requerido. | [Order `GET /orders/{orderId}`](contracts/openapi/order.json#L202) |
| `POST http://127.0.0.1:8081/orders/{orderId}/cancel` | Objeto requerido; `reason` es opcional: `{"reason":"CUSTOMER_REQUEST"}`. | `Idempotency-Key` y `orderId` (UUID) requeridos. | [Order `POST /orders/{orderId}/cancel`](contracts/openapi/order.json#L330) |

Las escrituras deben repetir la misma `Idempotency-Key` para obtener un replay seguro; cambiar el
body usando una clave ya utilizada es un conflicto. En `PUT /products`, `expectedVersion` debe ser
la versión observada en `GET /products` o `GET /products/{productId}/stock`; una versión obsoleta
responde `409 Conflict`. Para una reposición incremental use `POST /restock`, no calcule un total
absoluto en el cliente.

`productId` y `orderId` son UUID devueltos por los respectivos `POST`. Los cuerpos REST están
definidos en `contracts/openapi/`. Para detener conservando datos: `docker compose down`.

Para cargar un escenario repetible con cuatro productos y pedidos confirmado, rechazado y
cancelado, ejecute después del arranque:

```text
docker compose --profile demo-data run --build --rm demo-data
```
El comando usa únicamente las APIs públicas, verifica la convergencia y puede repetirse sin crear
operaciones adicionales ni volver a sumar stock.

Como alternativa, con Order e Inventory ya levantados y publicados en `localhost`, se puede
ejecutar directamente el script sin construir el contenedor:
```tex
python deploy/demo-data/populate_dummy_data.py --inventory-url http://localhost:18082 --order-url http://localhost:18081
```
La ejecución directa requiere Python 3.14 o compatible y usa las mismas APIs públicas que la
alternativa Compose.

## RabbitMQ: elección y garantía de entrega

Se eligió RabbitMQ porque el sistema necesita comunicación asíncrona operacional entre pocos servicios, routing, acknowledgements, reintentos y dead-letter queues. No requiere el throughput, la retención extensa ni las capacidades de streaming de Kafka; RabbitMQ también reduce el consumo de recursos y la complejidad del despliegue solicitado. Factores concretos de la implementación.

- **Garantía:** at-least-once. Mensajes persistentes, quorum queues durables, publicación enrutable con publisher confirms y ACK manual después del commit local.
- **Duplicados:** son esperados y tratados acordemente. Cada consumidor usa inbox transaccional, `eventId` estable e invariantes/versiones de agregado.
- **Reintentos:** tres demoras con TTL de 1, 5 y 30 segundos; transferencias a retry/DLQ confirmadas antes del ACK original y retorno TTL mediante dead-lettering at-least-once. No se usa requeue inmediato infinito. Reconexión y relay outbox usan backoff/jitter; outbox no descarta eventos por intentos agotados.
- **Mensajes no procesables:** errores permanentes o reintentos agotados pasan a una DLQ con causa, headers originales y procedimiento de replay. El replay conserva `eventId`.
- **Orden:** no se presupone orden global. `aggregateVersion`, locks por recurso y máquinas de estado resuelven eventos duplicados o fuera de orden.

Los ACK del consumidor y los publisher confirms no constituyen two-phase commit. PostgreSQL y RabbitMQ se coordinan mediante transactional outbox/inbox, aceptando duplicados controlados en lugar de una transacción distribuida.

## Observabilidad

El perfil opcional de observabilidad se inicia con `docker compose -f compose.yaml -f
deploy/compose/observability.yaml --profile observability up --build --wait`.
Grafana queda en
`http://127.0.0.1:13000`, Prometheus en `http://127.0.0.1:19090`, y las APIs observadas en
`18081`/`18082`; el despliegue base no inicia ni expone este stack.

Para generar actividad continua visible en Grafana, inicia el perfil de observabilidad y ejecuta
ciclos con claves nuevas:
```text
docker compose -f compose.yaml -f deploy/compose/observability.yaml --profile observability up --build --wait
docker compose -f compose.yaml -f deploy/compose/observability.yaml --profile observability --profile demo-data run --build --rm demo-data --periodic --interval-seconds 10 --cycles 12 --run-id grafana
```
Cada ciclo crea un lote por cada valor de `--orders-per-cycle`; cada lote crea un producto, repone stock y genera una orden confirmada, una cancelada y una rechazada. El valor predeterminado es `1`. `--interval-seconds` admite decimales y `0` para iniciar el siguiente ciclo inmediatamente;  `--cycles 0` mantiene el proceso hasta interrumpirlo. Después de iniciar la actividad, espera 10--20 segundos y actualiza Grafana.

Si Order e Inventory ya están publicados en `localhost:18081` y `localhost:18082`, se puede
ejecutar el generador directamente, sin construir el contenedor `demo-data`:
```text
python deploy/demo-data/populate_dummy_data.py --inventory-url http://localhost:18082 --order-url http://localhost:18081 --periodic --interval-seconds 1 --orders-per-cycle 5 --cycles 12 --run-id grafana
```
Los datos demo no tienen una API pública de borrado. Para limpiar las bases locales junto con el
stack, detén el perfil y elimina sus volúmenes:
```text
docker compose -f compose.yaml -f deploy/compose/observability.yaml --profile observability --profile demo-data down -v
```
Esto borra todos los datos locales de Order e Inventory, no solo los creados por `demo-data`.

## Verificación reproducible

Las puertas multiplataforma se ejecutan desde el root con Java 26:
```text
java scripts/Verify.java quick
java scripts/Verify.java full
java scripts/Verify.java contracts
java scripts/Verify.java acceptance
java scripts/Verify.java concurrency
java scripts/Verify.java constrained
java scripts/Verify.java chaos
java scripts/Verify.java observability
```
En Windows también están disponibles `scripts/verify.cmd`; en Linux/macOS, `scripts/verify.sh`.
El wrapper incluido usa Maven 3.9.16; alternativamente puede usarse Maven instalado con
`mvn -B -ntp clean verify`. Cada ejecución deja versiones, configuración, límites, resultados y
logs sanitizados en `reports/verification/<suite>/<run-id>/`.

La colección Bruno versionada cubre los endpoints, errores y las transiciones de la Saga:
[tests/bruno/README.md](tests/bruno/README.md). El replay controlado de DLQ (Dead-Letter Queue) está documentado en
[DLQ-REPLAY.md](docs/operations/DLQ-REPLAY.md).

`concurrency` ejecuta carreras deterministas, el verificador de invariantes y un smoke k6 con dos
réplicas de Order e Inventory. `constrained` añade cuotas efectivas y presión corta. `chaos` usa
Toxiproxy, aplica una latencia temporal a RabbitMQ y comprueba la recuperación. La campaña opcional
Linux con `tc/netem` está en `scripts/netem.sh`; requiere `tc`, `nsenter` y privilegios, y no es
necesaria para el desarrollo normal.

## Desarrollo y verificación

Para ejecutar el runner se requiere Java 26; Docker es necesario para Testcontainers y Compose.
Maven no es obligatorio: el wrapper incluido fija Maven 3.9.16.
```text
java scripts/Verify.java quick
java scripts/Verify.java full
java scripts/Verify.java acceptance
```
En Windows también se puede usar `scripts\verify.cmd quick`, `scripts\verify.cmd full` y
`scripts\verify.cmd acceptance`; en POSIX, `sh scripts/verify.sh quick` usa el mismo runner.
Cada ejecución deja metadata, logs sanitizados y resultados en
`reports/verification/<suite>/<run-id>/`. k6 conserva su resumen y los identificadores de instancia
observados junto con el log de la ejecución.

### Estado de las suites

`constrained` ejecuta el smoke de cuotas efectivas, demo-data, Bruno y presión k6 bajo el override
versionado. `chaos` ejecuta el perfil Toxiproxy y conserva configuración, logs y estado de colas.
`property` y `fuzz` siguen pendientes y fallan explícitamente.

### Maven

Wrapper incluido:
```text
./mvnw -B -ntp clean verify
mvnw.cmd -B -ntp clean verify
```
Maven instalado:
```text
mvn -B -ntp clean verify
```

Ambas opciones requieren Java 26; el wrapper descarga Maven la primera vez.

## Alcance y estado

Los endpoints base, reservas atómicas, cancelaciones, outbox/inbox, idempotencia HTTP, contratos,
carreras deterministas, invariantes, réplicas, cuotas y caos corto están implementados. Hay pruebas
automatizadas y un workflow de CI preparado; su ejecución remota requiere inicializar y publicar el
repositorio. El informe de verificación distingue las comprobaciones ejecutadas de las pendientes.

Compose local usa un nodo RabbitMQ (una sola instancia). La consistencia eventual requiere recuperación de dependencias y replay de mensajes en DLQ cuando corresponda. Quedan pendientes fuzzing extensivo y publicación CD en GHCR.
