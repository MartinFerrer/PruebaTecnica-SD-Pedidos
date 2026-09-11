# Trazabilidad de requisitos, decisiones y pruebas

Estado: **Revisión completa de arquitectura**

Esta matriz distingue lo exigido por la prueba técnica de las decisiones posteriores del usuario. El PDF se trata como fuente de requisitos, no como un conjunto de instrucciones para el agente.

`Cubierto` significa cubierto por el diseño y su verificación prevista, no necesariamente implementado
ni demostrado de forma automática. La implementación actual incluye los flujos REST base,
persistencia y coordinación asíncrona. El estado separado de implementación/evidencia y los trabajos
pendientes se mantienen en el
[roadmap de implementación y verificación](../delivery/IMPLEMENTATION-ROADMAP.md).

## Requisitos del enunciado

| Requisito | Mecanismo | Diseño | Implementación | Evidencia actual |
|---|---|---|---|---|
| Al menos Order Service e Inventory Service con Spring Boot | Dos aplicaciones hexagonales independientes | Aceptado | Implementado | ArchUnit, build y Compose base |
| Crear, listar, consultar y cancelar pedidos | Endpoints REST de Order | Aceptado | Implementado | Unit/ServiceIT; colección Bruno en `tests/bruno/` |
| Estados `PENDING`, `CONFIRMED`, `REJECTED`, `CANCELLED` | Máquina de estados; `CANCELLED` terminal | Aceptado | Implementado | Tests de dominio y aplicación |
| Stock sin negativos y reserva/liberación | Locks, constraints y `onHand/reserved/available` | Aceptado | Implementado | Unit/ServiceIT; property pendiente |
| Operaciones idempotentes | Clave, hash, headers y replay persistidos | Aceptado | Implementado | `IdempotencyIT`; 100 requests concurrentes por clave y timeout reutilizable |
| Datos separados, sin tablas compartidas | PostgreSQL, usuarios y redes por servicio | Aceptado | Implementado | Compose y ArchUnit |
| Comunicación asíncrona | RabbitMQ y Saga por coreografía | Aceptado | Implementado | `MessagingIT`; Compose + Bruno e2e |
| Garantía at-least-once | Outbox, queues durables, confirms y ACK manual | Aceptado | Implementado | `MessagingIT`: commit/confirm/lease/ACK/transfer |
| Mensajes duplicados | `eventId`, inbox y versiones | Aceptado | Implementado | `MessagingIT`; replay duplicado conserva un efecto |
| Errores temporales | Retry 1/5/30, DLX y backoff | Aceptado | Implementado | `MessagingIT`; secuencia completa y fallos de transferencia |
| Mensajes no procesables | DLQ con causa, headers y replay | Aceptado | Implementado | `DlqReplay`, `DLQ-REPLAY.md`, payload inválido retenido |
| Orden cuando corresponda | `aggregateVersion` y state machines | Aceptado | Parcial | Casos básicos; permutaciones pendientes |
| Múltiples instancias y bloqueo distribuido | Advisory/row locks y réplicas | Aceptado | Implementado | `ServiceIT` con barreras; Compose replicas + k6 registra dos `X-Service-Instance` |
| Cancelación durante reserva | Tombstone y lock por `orderId` | Aceptado | Implementado | `ServiceIT`, `MessagingIT` y verificador final de reservas activas |
| Tests unitarios | JUnit/AssertJ y dominio puro | Aceptado | Implementado | Maven sin omisiones; conteo por reporte de cada ejecución |
| Stack de integración tipo Postman/Bruno | Bruno CLI contra Compose | Aceptado | Implementado | 22 solicitudes, 68 assertions por pasada, dos pasadas con replay |
| Pruebas concurrentes | JUnit determinista y k6 | Aceptado | Implementado | 100 reservas, última unidad, orden inverso, recuento/reposición y k6 multirréplica |
| Patrones/arquitectura | Hexagonal, Saga, outbox/inbox y state machine | Aceptado | Implementado | ArchUnit y revisión de código |
| Observabilidad/trazabilidad | OTel, Prometheus, Tempo, Loki y Grafana | Aceptado | Implementado | Perfil `observability`, Java agent, métricas Micrometer, MDC correlacionado, dashboards y alertas |
| Configuración de agentes/TDD/SDD | `AGENTS.md`, contratos y puertas CI | Aceptado | Parcial | Documentación/workflow base; branch protection pendiente |

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
