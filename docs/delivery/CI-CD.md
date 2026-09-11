# CI y preparación de release

El repositorio usa GitHub Actions con permisos de solo lectura, acciones fijadas por SHA y los
mismos comandos `java scripts/Verify.java` disponibles localmente. Checkstyle informa advertencias
sin imponer formato; las puertas funcionales sí bloquean el resultado.

## Evidencia remota

La auditoría del 11 de septiembre de 2026 revisó ejecuciones reales del repositorio privado:

- [ejecución verde de referencia](https://github.com/MartinFerrer/PruebaTecnica-SD-Pedidos/actions/runs/34605549152);
- [ejecución que detectó la incompatibilidad de Testcontainers en `windows-latest`](https://github.com/MartinFerrer/PruebaTecnica-SD-Pedidos/actions/runs/34642530814).

El segundo run confirmó que Java 26, Maven Wrapper y los tests sin infraestructura funcionan en
ambos sistemas. El fallo de Windows no era funcional: el runner hospedado exponía Docker en modo
Windows, no compatible con los contenedores Linux de Testcontainers. Por eso las comprobaciones
portables conservan la matriz Windows/Linux y las puertas Docker se ejecutan en Ubuntu.

El mismo run mostró en Ubuntu que Bruno completaba las solicitudes pero no podía crear el reporte
JUnit en el volumen del host. El runner ahora prepara su directorio con permisos de escritura para
el contenedor. Ambas correcciones requieren un nuevo run verde sobre el commit que se etiquetará.

La evidencia definitiva para un tag debe ser el run verde de `quality-gate` generado por el commit
que se etiquetará; no se debe reutilizar un run de un commit anterior.

## Puertas de pull request y `main`

| Job | Sistemas | Entrada estable | Cobertura |
|---|---|---|---|
| `static-checks` | Ubuntu y Windows | `java scripts/Verify.java static` | OpenAPI, AsyncAPI, contratos observados y advertencias Checkstyle. |
| `unit` | Ubuntu y Windows | `quick`, `property`, `fuzz` | Unitarios, ArchUnit, propiedades y fuzz smoke reproducible. |
| `integration` | Ubuntu | `java scripts/Verify.java full` | PostgreSQL/RabbitMQ reales, Flyway, idempotencia, outbox/inbox, retries, DLQ y cobertura. |
| `container` | Ubuntu | `java scripts/Verify.java container` | Validación Compose y build de ambas imágenes. |
| `e2e` | Ubuntu | `java scripts/Verify.java acceptance` | Arranque, healthchecks, demo replay y colección Bruno. |
| `concurrency-smoke` | Ubuntu | `concurrency`, `constrained` | Carreras deterministas, invariantes, dos réplicas, k6 y cuotas efectivas. |
| `observability-smoke` | Ubuntu | `java scripts/Verify.java observability` | Perfil OTel/Prometheus/Tempo/Loki/Grafana y descubrimiento de targets. |
| `quality-gate` | Ubuntu | agregador | Falla si cualquier puerta requerida falla, se cancela o se omite. |

Los jobs pesados dependen de las puertas rápidas para evitar gasto cuando contratos o unitarios ya
fallan. La protección de `main` debe requerir el check estable `quality-gate`, pull request y rama
actualizada; debe impedir force-push y borrado.

## Campañas programadas y manuales

`.github/workflows/extended-verification.yaml` se ejecuta manualmente y semanalmente. Separa cuatro
campañas Linux: 20 000 entradas de fuzzing sembrado, presión de recursos, caos de red con Toxiproxy
y resiliencia de mensajería/concurrencia. Cada una conserva su semilla y configuración efectiva.

## Reportes y secretos

Cada job sube únicamente `reports/verification/` y, para integración, cobertura JaCoCo. La retención
es de siete días. El runner registra Java, Maven, Docker, imágenes, semilla, límites y fallos
inyectados, redacta variables con nombres sensibles y no sube bases de datos ni archivos `.env`.

## Release `v1.0.0`

El commit de release debe estar limpio, enviado a `main` y asociado a un `quality-gate` verde. El
tag anotado se crea sobre ese mismo commit y se comprueba antes de publicarlo:

```text
git status --short
git add -A
git commit -m "Validar CI y preparar la demo para la versión 1.0.0"
git push origin main
# Esperar que quality-gate finalice correctamente para el commit anterior.
git tag -a v1.0.0 -m "Versión 1.0.0"
git show --no-patch --decorate v1.0.0
git push origin v1.0.0
```
