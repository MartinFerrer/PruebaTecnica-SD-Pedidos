# Sistema distribuido de pedidos e inventario

Primera implementación funcional de Order e Inventory, con contratos REST/eventos, persistencia separada y coordinación asíncrona. La fase sigue siendo `IMPLEMENTACION`.

La arquitectura acordada usa Java 26, Spring Boot 4.1.1, arquitectura hexagonal, PostgreSQL, RabbitMQ, Docker Compose, pruebas automatizadas y CI/CD en GitHub.

## Documentación final

- [Arquitectura del sistema](docs/architecture/ARCHITECTURE.md)
- [Flujos y contratos asíncronos](docs/architecture/EVENTS-AND-RACES.md)
- [Trazabilidad de requisitos y decisiones](docs/architecture/REQUIREMENTS-TRACEABILITY.md)
- [Estrategia de pruebas](docs/testing/TEST-STRATEGY.md)
- [Diseño de CI/CD](docs/delivery/CI-CD.md)
- [Observabilidad y telemetría](docs/operations/OBSERVABILITY.md)
- [Despliegue local y datos de demostración](docs/operations/LOCAL-DEPLOYMENT.md)
- [Reglas para agentes](AGENTS.md)
- [Verificación y pendientes de implementación](docs/testing/IMPLEMENTATION-VERIFICATION.md)

## Ejecutar localmente

Para desplegar solo se requiere Docker Desktop/Engine con Compose v2. Copiar `.env.example` a `.env` y establecer contraseñas locales antes del primer arranque; no versionar `.env`. El build instala Maven dentro de su etapa temporal, por lo que no depende de herramientas específicas del sistema anfitrión.

```text
docker compose up --build --wait --wait-timeout 180
```

- Order: `http://127.0.0.1:8081/orders`.
- Inventory: `http://127.0.0.1:8082/products`.
- RabbitMQ Management: `http://127.0.0.1:15672`, usuario `app`, contraseña configurada en `.env`.
- Readiness de cada API: `/actuator/health/readiness`.

Para probar desde Postman no hace falta una configuración especial. La tabla reúne todos los
endpoints REST básicos; cada enlace lleva a la operación y al esquema de body correspondiente en
el contrato OpenAPI.

| Servicio y operación | Body | Headers y parámetros | Especificación detallada |
|---|---|---|---|
| `GET http://127.0.0.1:8082/products` | No lleva body. Lista todos los productos y su stock. | Ninguno. | [Inventory `GET /products`](contracts/openapi/inventory.json#L13) |
| `POST http://127.0.0.1:8082/products` | Requerido: `{"sku":"SKU-001","name":"Keyboard","initialStock":20}`. | `Idempotency-Key` requerido. | [Inventory `POST /products`](contracts/openapi/inventory.json#L30) |
| `PUT http://127.0.0.1:8082/products` | Requerido: `{"productId":"<UUID>","stock":25,"expectedVersion":1,"reason":"MANUAL_RECOUNT"}`. | `Idempotency-Key` requerido. | [Inventory `PUT /products`](contracts/openapi/inventory.json#L133) |
| `POST http://127.0.0.1:8082/products/{productId}/restocks` | Requerido: `{"movementId":"<UUID>","quantity":5,"reason":"RECEIVING"}`. | `Idempotency-Key` y `productId` (UUID) requeridos. | [Inventory `POST /products/{productId}/restocks`](contracts/openapi/inventory.json#L261) |
| `GET http://127.0.0.1:8082/products/{productId}/stock` | NA | `productId` (UUID) requerido. | [Inventory `GET /products/{productId}/stock`](contracts/openapi/inventory.json#L383) |
| `GET http://127.0.0.1:8081/orders` | NA. Lista todas las órdenes y su estado actual. | Ninguno. | [Order `GET /orders`](contracts/openapi/order.json#L13) |
| `POST http://127.0.0.1:8081/orders` | Requerido: `{"items":[{"productId":"<UUID>","quantity":2}]}`. | `Idempotency-Key` requerido. | [Order `POST /orders`](contracts/openapi/order.json#L31) |
| `GET http://127.0.0.1:8081/orders/{orderId}` | NA | `orderId` (UUID) requerido. | [Order `GET /orders/{orderId}`](contracts/openapi/order.json#L200) |
| `POST http://127.0.0.1:8081/orders/{orderId}/cancel` | Objeto requerido; `reason` es opcional: `{"reason":"CUSTOMER_REQUEST"}`. | `Idempotency-Key` y `orderId` (UUID) requeridos. | [Order `POST /orders/{orderId}/cancel`](contracts/openapi/order.json#L328) |

Las escrituras deben repetir la misma `Idempotency-Key` para obtener un replay seguro; cambiar el
body usando una clave ya utilizada es un conflicto. En `PUT /products`, `expectedVersion` debe ser
la versión observada en `GET /products` o `GET /products/{productId}/stock`; una versión obsoleta
responde `409 Conflict`. Para una reposición incremental use `POST /restocks`, no calcule un total
absoluto en el cliente.

`productId` y `orderId` son UUID devueltos por los respectivos `POST`. Los cuerpos REST están
definidos en `contracts/openapi/`. Para detener conservando datos: `docker compose down`.

## Desarrollo y verificación

Para compilar y ejecutar las pruebas fuera de Docker se requiere Java 26, Maven 3.9+ y Docker para Testcontainers. Configurar `JAVA_HOME`, Maven y Docker en `PATH`.

```text
mvn -B -ntp clean verify
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
```

El formato Java queda bajo responsabilidad manual del equipo; CI no reescribe ni impone un formateador. La verificación comprueba unitarios, integración con Testcontainers, ArchUnit, cobertura y configuración/arranque de Compose. Las pruebas de carga y recursos limitados usadas durante el bootstrap fueron artefactos locales y no forman parte del repositorio.

## RabbitMQ: elección y garantía de entrega

Se eligió RabbitMQ porque el sistema necesita comunicación asíncrona operacional entre pocos servicios, routing, acknowledgements, reintentos y dead-letter queues. No requiere el throughput, la retención extensa ni las capacidades de streaming de Kafka; RabbitMQ también reduce el consumo de recursos y la complejidad del despliegue solicitado.

- **Garantía:** at-least-once. Mensajes persistentes, quorum queues durables, publicación enrutable con publisher confirms y ACK manual después del commit local.
- **Duplicados:** son esperables. Cada consumidor usa inbox transaccional, `eventId` estable e invariantes/versiones de agregado.
- **Reintentos:** tres demoras con TTL de 1, 5 y 30 segundos; transferencias a retry/DLQ confirmadas antes del ACK original y retorno TTL mediante dead-lettering at-least-once. No se usa requeue inmediato infinito. Reconexión y relay outbox usan backoff/jitter; outbox no descarta eventos por intentos agotados.
- **Mensajes no procesables:** errores permanentes o reintentos agotados pasan a una DLQ con causa, headers originales y procedimiento de replay. El replay conserva `eventId`.
- **Orden:** no se presupone orden global. `aggregateVersion`, locks por recurso y máquinas de estado resuelven eventos duplicados o fuera de orden.

Los ACK del consumidor y los publisher confirms no constituyen two-phase commit. PostgreSQL y RabbitMQ se coordinan mediante transactional outbox/inbox, aceptando duplicados controlados en lugar de una transacción distribuida.

## Operaciones de stock

| Intención | Endpoint | Consistencia |
|---|---|---|
| Crear producto | `POST /products` | Stock inicial, SKU único e idempotencia HTTP |
| Recibir nuevas unidades | `POST /products/{productId}/restocks` | Suma atómica y un `movementId` persistente por recepción |
| Recuento físico manual | `PUT /products` | Cantidad absoluta, `expectedVersion` y nunca menos que lo reservado |

Todas las escrituras exigen `Idempotency-Key`. Los replays no repiten movimientos ni eventos. Reponer no debe implementarse calculando un total en el cliente y enviándolo por PUT.

## Alcance y estado

Los endpoints base, reservas atómicas, cancelaciones, outbox/inbox e idempotencia están implementados. Hay pruebas automatizadas y un workflow de CI preparado; su ejecución remota requiere inicializar y publicar el repositorio. El informe de verificación distingue las comprobaciones ejecutadas de las pendientes.

Compose local usa un nodo RabbitMQ y no ofrece HA. La consistencia eventual requiere recuperación de dependencias y replay de mensajes en DLQ cuando corresponda. Autenticación, pagos y despacho quedan fuera del alcance; no exponer este despliegue de desarrollo a Internet. Quedan pendientes el dataset estable, los perfiles de observabilidad y caos de red, fuzzing extensivo y publicación CD en GHCR.
