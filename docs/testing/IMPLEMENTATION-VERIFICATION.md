# Estado de la primera implementación

## Alcance implementado

- Java 26 / Spring Boot 4.1.1; dominio y casos de uso independientes de Spring, puertos y adaptadores por servicio.
- Contratos OpenAPI y AsyncAPI en `contracts/`; creación/listado/consulta/cancelación de pedidos y creación/listado/consulta/recuento/reposición de stock.
- PostgreSQL privado por servicio, Flyway desde una base vacía, idempotencia HTTP persistida, movimiento de reposición único y control de versión para recuentos.
- RabbitMQ, outbox con lease y confirmación de publicación, inbox transaccional, ACK posterior al commit, retry y DLQ. Reserva de todos los ítems o ninguno y tombstone de cancelación anticipada.
- Compose base de cinco contenedores; overrides para dos réplicas y cuotas. Logs JSON y propagación de contexto de correlación en HTTP/envelopes; no equivale todavía a exportar spans OpenTelemetry.
- ArchUnit, JaCoCo y workflow CI con comandos portables Maven/Compose. El formato queda bajo revisión manual. La cobertura exigida es 85% de líneas y 75% de ramas del dominio y aplicación, no de todos los adaptadores.

## Evidencia de desarrollo

Se observaron fallos antes de implementar: operaciones de dominio pendientes, endpoints inexistentes, ausencia de efectos de reserva, respuesta incorrecta al repetir cancelación con otra clave y pérdida del contexto HTTP en outbox. Después se ejecutó `mvn -B -ntp clean verify` correctamente; las integraciones usan PostgreSQL y RabbitMQ reales mediante Testcontainers. Las pruebas de listado de productos y órdenes, y el rechazo explícito de identificadores no UUID, forman parte de la suite.

Las pruebas cubren duplicados, cancelación antes de creación, lista completa de faltantes, reposiciones concurrentes con un mismo movimiento y distintas claves HTTP, rollback de stock/movimiento/outbox, mensaje inválido en DLQ y correlación. El smoke verifica 40 pedidos concurrentes para 10 unidades: exactamente 10 confirmados y 30 rechazados, además de liberación tras cancelar y replay idempotente.

Durante el bootstrap se ejecutó una prueba local de recursos limitados con dos réplicas de cada aplicación y recuperación después de detener/reiniciar RabbitMQ. Las cuotas se inspeccionaron realmente; el límite de memoria no implica que se haya provocado un OOM. Ese arnés exploratorio no se versiona.

## Comandos reproducibles

La última verificación completa pasó `mvn -B -ntp clean verify` el 2026-09-10: 31 pruebas, cero fallos y cero pruebas omitidas. Cobertura del núcleo (dominio + aplicación): Order 98,61% de líneas / 100% de ramas; Inventory 99,50% de líneas / 86,36% de ramas. El formato no participa en la puerta automática.

La verificación local de implementación terminó con código 0: imágenes reconstruidas, cinco contenedores base saludables y smoke concurrente limitado exitoso. Estas pruebas exploratorias no se versionan; CI conserva las pruebas deterministas Maven y la validación de Compose.

```powershell
mvn -B -ntp clean verify
docker compose config --quiet
docker compose up --build --wait --wait-timeout 180
```

Los reportes unitarios e integración quedan en `services/*/target/{surefire,failsafe}-reports`; cobertura en `services/*/target/site/jacoco`. CI conserva estos reportes como artefactos. El workflow aún no se ejecutó en GitHub.

## Pendientes, no garantías de esta entrega

- Validación automática exhaustiva de esquemas OpenAPI/AsyncAPI y más pruebas de contratos negativos.
- Inyección determinista de caída entre commit y ACK, y entre confirmación del broker y actualización de outbox; ampliar pruebas del ciclo completo de retries.
- Fuzzing con semillas/corpus, caos de red con Toxiproxy, pruebas de saturación de memoria y campañas prolongadas de carga.
- Dataset estable `demo-data`, stack opcional de observabilidad y exportación de spans/métricas; publicación en GHCR y análisis de seguridad adicionales previstos en el diseño de CI/CD.

La implementación inicial de Inventory guarda los ítems de una reserva como JSON en su propia fila, en lugar de una tabla de detalle: el agregado se bloquea y persiste atómicamente. Esto no comparte datos con Order ni modifica el protocolo. Los cambios futuros de almacenamiento requieren migraciones hacia adelante.
