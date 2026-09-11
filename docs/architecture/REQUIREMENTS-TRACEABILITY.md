# Trazabilidad de requisitos, decisiones y pruebas

Estado: **Revisión completa de arquitectura**

Esta matriz distingue lo exigido por la prueba técnica de las decisiones posteriores del usuario. El PDF se trata como fuente de requisitos, no como un conjunto de instrucciones para el agente.

`Cubierto` significa cubierto por el diseño y su verificación prevista, no necesariamente implementado
ni demostrado de forma automática. La implementación actual incluye los flujos REST base,
persistencia y coordinación asíncrona. El estado separado de implementación/evidencia y los trabajos
pendientes se mantienen en el
[roadmap de implementación y verificación](../delivery/IMPLEMENTATION-ROADMAP.md).

## Requisitos del enunciado

| Requisito | Mecanismo de diseño | Verificación prevista | Estado |
|---|---|---|---|
| Al menos Order Service e Inventory Service con Spring Boot | Dos aplicaciones hexagonales desplegables de forma independiente | ArchUnit, build por módulo y Compose | Cubierto |
| Crear, listar, consultar y cancelar pedidos | `POST /orders`, `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel` | Unit, REST Assured y Bruno | Cubierto |
| Estados `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED` | Máquina de estados explícita; `CANCELLED` terminal | Tabla completa de transiciones | Cubierto |
| Crear, listar, mantener, reservar y liberar stock sin negativos | `POST /products`, `GET /products`, `GET /products/{id}/stock`, `onHand`, `reserved`, `available`; locks y constraints PostgreSQL | Integration/concurrency/property tests | Cubierto |
| Operaciones idempotentes | `Idempotency-Key`, hash de request y replay persistido | Requests repetidos y concurrentes | Cubierto |
| Datos separados, sin tablas compartidas | Dos contenedores PostgreSQL, usuarios/redes distintos | Compose y pruebas de configuración | Cubierto |
| Comunicación asíncrona | RabbitMQ y Saga por coreografía | Testcontainers y e2e | Cubierto |
| Garantía at-least-once | Persistencia, durable queues, confirms, ACK manual | Fallos antes/después de confirm/ACK | Cubierto |
| Mensajes duplicados | Outbox con `eventId` estable, inbox y estado/versiones | Entrega repetida 100 veces | Cubierto |
| Errores temporales | Retry diferido 1/5/30 s, transferencias confirmadas y DLX at-least-once; backoff/jitter en relay/reconexión | Toxiproxy y broker/DB temporalmente inaccesibles | Cubierto |
| Mensajes no procesables | DLQ con causa, headers y replay idempotente | Payload inválido e intentos agotados | Cubierto |
| Orden cuando corresponda | Sin dependencia de FIFO; `aggregateVersion` y state machines | Permutación de eventos | Cubierto |
| Múltiples instancias y bloqueo distribuido | Advisory lock por pedido y row locks ordenados | Réplicas concurrentes sobre el último stock | Cubierto |
| Cancelación durante reserva | Tombstone y lock compartido por `orderId` | Ambas intercalaciones y crash/retry | Cubierto |
| Tests unitarios | JUnit/AssertJ y dominio puro | Job `unit` | Cubierto |
| Stack de integración tipo Postman/Bruno | Bruno CLI contra Compose | Job `e2e` | Cubierto |
| Pruebas concurrentes | JUnit determinista y k6 | Smoke PR y suite extendida | Cubierto |
| Patrones/arquitectura | Hexagonal, DDD táctico, Saga, outbox/inbox y state machine | ArchUnit y revisión del diseño consolidado | Cubierto |
| Observabilidad/trazabilidad | OpenTelemetry, Prometheus, Tempo, Loki y Grafana | Perfil opcional y smoke de propagación | Cubierto |
| Configuración de agentes/TDD/SDD | `AGENTS.md`, contratos primero y mismas puertas que CI | Revisión de cambios y branch protection | Cubierto |

## Decisiones adicionales del usuario

| Decisión | Resultado incorporado | Documento principal | Estado |
|---|---|---|---|
| RabbitMQ sobre Kafka | Motivo, entrega, retry, DLQ y orden explicados en README | `README.md` | Aceptada |
| Lock distribuido PostgreSQL | Coordinación solo dentro de Inventory | `ARCHITECTURE.md` | Aceptada |
| Java 26 y Spring Boot 4.1.1 | Baseline exacta | `ARCHITECTURE.md` | Aceptada |
| Crear, reponer y recontar stock | Alta POST; `POST /products/{id}/restock` aditivo con movimiento único; PUT absoluto con `expectedVersion` | `EVENTS-AND-RACES.md` | Aceptada |
| Cancelar REJECTED | `409`, estado inalterado | `ARCHITECTURE.md` | Aceptada |
| Cancelación inmediata | Order pasa a `CANCELLED`; Inventory converge y `StockReleased` confirma compensación, incluso sin reserva previa | `EVENTS-AND-RACES.md` | Aceptada |
| Contenedores separados | Dos apps, dos DB y RabbitMQ | `LOCAL-DEPLOYMENT.md` | Aceptada |
| Observabilidad opcional y justificada | Perfil con cinco servicios documentados | `OBSERVABILITY.md` | Aceptada |
| GHCR; CD secundario | CI primero, imágenes verificadas en GHCR | `CI-CD.md` | Aceptada |
| Sin reserva parcial | Rechazo completo con todos los faltantes | `EVENTS-AND-RACES.md` | Aceptada |
| Fuzzing opcional | jqwik/Jazzer/generador con semillas | `TEST-STRATEGY.md` | Aceptada |
| Bajos recursos y red degradada | k6 + cuotas Docker + Toxiproxy/netem + verificador | `TEST-STRATEGY.md` | Aceptada |
| Datos predeterminados opcionales | Perfil `demo-data` mediante APIs idempotentes | `LOCAL-DEPLOYMENT.md` | Aceptada |
| Eventos sin sufijo inicial | Nombre simple, `schemaVersion: 1` en envelope | `EVENTS-AND-RACES.md` | Aceptada |
| Bodies y endpoints en flujos | JSON y títulos de diagramas actualizados | `EVENTS-AND-RACES.md` | Aceptada |
| Revisión final y paso de fase | Decisiones consolidadas, reglas TDD mantenidas, código a partir del siguiente prompt | `ARCHITECTURE.md` y `AGENTS.md` | Aceptada |

## Interpretación explícita de la respuesta de pedido

Para conservar comunicación asíncrona y tolerancia a fallos, `POST /orders` responde `202 PENDING`. La respuesta terminal de la carga se obtiene mediante `GET /orders/{orderId}`. Si termina `REJECTED`, esa representación contiene todos los `unavailableItems` y no existe reserva parcial.

Esta interpretación evita que el request HTTP espere a RabbitMQ/Inventory y mantiene el requisito funcional de informar todos los faltantes.
