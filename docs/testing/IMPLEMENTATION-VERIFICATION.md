# Estado de la primera implementación

## Alcance implementado

- Java 26 / Spring Boot 4.1.1; dominio y casos de uso independientes de Spring, puertos y adaptadores por servicio.
- Contratos OpenAPI y AsyncAPI en `contracts/`; creación/listado/consulta/cancelación de pedidos y creación/listado/consulta/recuento/reposición de stock.
- PostgreSQL privado por servicio, Flyway desde una base vacía, idempotencia HTTP persistida, movimiento de reposición único y control de versión para recuentos.
- RabbitMQ, outbox con lease y confirmación de publicación, inbox transaccional, ACK posterior al commit, retry y DLQ. Reserva de todos los ítems o ninguno y tombstone de cancelación anticipada.
- Compose base de cinco contenedores, un runner one-shot `demo-data`, overrides para réplicas/cuotas, Toxiproxy y observabilidad. Logs JSON, contexto HTTP/envelopes, spans OTLP y header de instancia.
- ArchUnit, checks iniciales de contratos, JaCoCo y workflow CI con comandos portables Maven/Compose. El formato queda bajo revisión manual. La cobertura exigida es 85% de líneas y 75% de ramas del dominio y aplicación, no de todos los adaptadores.
- `shared-library` centraliza solo infraestructura repetida (topología RabbitMQ, confirmación, outbox, JSON, correlación, idempotencia/transacciones HTTP, contratos de eventos, respuesta de error y mecánica transaccional de consumidores); una regla arquitectónica evita dependencias hacia los dominios. Las excepciones de Order e Inventory permanecen en sus propios servicios.

## Evidencia de desarrollo

Se observaron fallos antes de implementar: operaciones de dominio pendientes, endpoints inexistentes, ausencia de efectos de reserva, respuesta incorrecta al repetir cancelación con otra clave y pérdida del contexto HTTP en outbox. Después se ejecutó `mvn -B -ntp clean verify` correctamente; las integraciones usan PostgreSQL y RabbitMQ reales mediante Testcontainers. Las pruebas de listado de productos y órdenes, y el rechazo explícito de identificadores no UUID, forman parte de la suite.

Las pruebas cubren duplicados, cancelación antes de creación, lista completa de faltantes, 100 reservas concurrentes para 10 unidades, reposiciones concurrentes con un mismo movimiento y distintas claves HTTP, dos reconteos sobre la misma versión, pedidos multítem en orden inverso, colisión global de `movementId`, rollback de stock/movimiento/outbox, mensajes inválidos en DLQ, validación de resultados de reserva/liberación y correlación. El verificador independiente compara movimientos efectivos, stock y reservas activas.

El arnés versionado de recursos limitados ejecuta dos réplicas de cada aplicación, inspecciona las cuotas efectivas y registra presión, reinicios y backlog. El límite de memoria no implica que se haya provocado un OOM; las campañas severas quedan fuera del smoke de PR.

## Comandos reproducibles

La verificación completa se ejecuta mediante `java scripts/Verify.java full` y usa Maven Wrapper 3.9.16, PostgreSQL/RabbitMQ reales mediante Testcontainers. `java scripts/Verify.java concurrency` agrega las carreras y k6 multirréplica; `constrained` agrega cuotas/presión y `chaos` agrega Toxiproxy. El formato no participa en la puerta automática.

El análisis más reciente se ejecutó con `jscpd 5.2.0 --min-lines 5 --min-tokens 50` sobre los Java de `services/`, ignorando `target/` y eliminando sus reportes temporales. En el refactor actual las líneas duplicadas bajaron de 9,91% a 7,91%. El número bruto de clones pasó de 22 a 25 porque los puertos y DTO se dividieron en archivos pequeños; no representa más código duplicado. Los clones restantes corresponden sobre todo a configuración por servicio, excepciones de dominio que deben permanecer aisladas, adaptadores del mismo protocolo y pruebas de integración; no se comparte una biblioteca de dominio ni se introduce una abstracción que oculte diferencias reales entre los bounded contexts.

La verificación local de implementación terminó con código 0: imágenes reconstruidas, contenedores saludables, dos ejecuciones consecutivas de `demo-data`, carreras deterministas, smoke k6 multirréplica, cuotas efectivas y chaos corto exitosos. Los reportes completos quedan bajo `reports/verification/<suite>/<run-id>`; CI conserva estas puertas mediante el mismo runner.

Después del renombrado y la extracción técnica, `docker compose config --quiet` y `docker compose build order-service inventory-service` también terminaron correctamente el 2026-09-11; ambas imágenes compilan el reactor incluyendo `shared-library`.

```text
java scripts/Verify.java quick
java scripts/Verify.java full
java scripts/Verify.java acceptance
```

El runner de M0 deja metadata, logs sanitizados, versiones de herramientas, configuración,
imágenes y reportes Maven en `reports/verification/<suite>/<run-id>/`. Las suites `property` y
`fuzz` sigue siendo explícitamente pendiente; no se cuenta como ejecutada. `observability` valida el
arranque del perfil, healthchecks y descubrimiento de Order, Inventory y RabbitMQ en Prometheus.

Los reportes unitarios e integración quedan en `services/*/target/{surefire,failsafe}-reports`; cobertura en `services/*/target/site/jacoco`. CI conserva estos reportes como artefactos. `property` conserva la semilla jqwik y `fuzz` conserva la semilla y el corpus utilizado. El workflow aún no se ejecutó en GitHub.

## Pendientes, no garantías de esta entrega

El backlog ordenado, sus dependencias, pruebas automáticas, validaciones manuales y criterios de
cierre se mantienen en el
[roadmap de implementación y verificación](../delivery/IMPLEMENTATION-ROADMAP.md). Resumen:

- Fuzzing guiado por cobertura extensivo, pruebas de saturación prolongada y campañas severas de caos.
- Publicación en GHCR y análisis de seguridad adicionales previstos en el diseño de CI/CD.

La implementación inicial de Inventory guarda los ítems de una reserva como JSON en su propia fila, en lugar de una tabla de detalle: el agregado se bloquea y persiste atómicamente. Esto no comparte datos con Order ni modifica el protocolo. Los cambios futuros de almacenamiento requieren migraciones hacia adelante.
