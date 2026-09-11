# Roadmap de implementación y verificación

Estado: **backlog verificado contra el repositorio**

Este documento convierte en trabajo ejecutable las decisiones ya aceptadas en
[ARCHITECTURE.md](../architecture/ARCHITECTURE.md),
[EVENTS-AND-RACES.md](../architecture/EVENTS-AND-RACES.md),
[REQUIREMENTS-TRACEABILITY.md](../architecture/REQUIREMENTS-TRACEABILITY.md),
[TEST-STRATEGY.md](../testing/TEST-STRATEGY.md),
[OBSERVABILITY.md](../operations/OBSERVABILITY.md) y [CI-CD.md](CI-CD.md). No reabre esas
decisiones. Ante una contradicción con los contratos OpenAPI/AsyncAPI, se detiene únicamente el
incremento afectado y se resuelve la fuente de verdad antes de cambiar código o pruebas.

`Cubierto` en la matriz de trazabilidad significa que existe un diseño y una verificación prevista;
no implica por sí solo que la implementación o la evidencia automática estén completas. En este
roadmap se usan estos estados:

- **Implementado:** existe código y evidencia automatizada reproducible.
- **Parcial:** existe parte del comportamiento o de la prueba, pero falta demostrar una garantía.
- **Pendiente:** no existe todavía una implementación versionada y verificable.

## Línea base observada

| Área | Estado actual | Evidencia o limitación |
|---|---|---|
| APIs y dominio | Implementado | Endpoints base de Order e Inventory, estados, stock físico/reservado, reposición aditiva y reconteo versionado. |
| Persistencia | Implementado | Dos PostgreSQL, Flyway, constraints, locks, movimientos e identidades persistentes. |
| Saga y mensajería | Implementado para M3 | Outbox/inbox, confirms, ACK manual, retry/DLQ, failpoints deterministas, assertions de topología y replay validado por convergencia. |
| Idempotencia HTTP | Implementado para M2 | Headers históricos, concurrencia, conflictos, errores definitivos, timeout de lock, migración V2->V3 y retry acotado de deadlock/serialización. |
| Contratos | Implementado para M1 | OpenAPI/AsyncAPI contra metaschemas versionados, schemas de eventos, validación observada request/response y colección Bruno repetible. |
| Pruebas | Implementado para M1-M3 | Maven, Testcontainers, Bruno en Compose, integración de mensajería, idempotencia, límites y constraints; property/fuzz/k6 permanecen en M4-M6. |
| Compose | Parcial | Base, `demo-data`, cuotas y réplicas existen; no existen perfiles versionados de observabilidad o caos ni arneses de invariantes. |
| Observabilidad | Pendiente | Hay Actuator health, logs ECS y contexto en el envelope; no hay Collector, Prometheus, Tempo, Loki, Grafana, spans exportados, métricas de negocio, dashboards o alertas. |
| CI | Parcial | Un workflow ejecuta Maven, Compose y `demo-data`; no se pudo confirmar una ejecución remota autenticada y aún no contiene todas las puertas diseñadas. |
| CD y seguridad de artefactos | Pendiente | No hay publicación GHCR, SBOM, escaneo de imágenes, attestations, release workflow ni actualización automática de dependencias. |

## Orden de entrega

| Orden | Hito | Prioridad | Depende de |
|---|---|---|---|
| M0 | Evidencia reproducible y trazabilidad honesta | P0 | — |
| M1 | Contratos y comportamiento HTTP completo | P0 | M0 |
| M2 | Idempotencia y persistencia bajo fallos | P0 | M1 |
| M3 | Entrega AMQP demostrable y replay seguro | P0 | M0 |
| M4 | Carreras, invariantes y múltiples réplicas | P0 | M2, M3 |
| M5 | Property tests y fuzzing reproducible | P1 | M2, M4 |
| M6 | Recursos limitados y caos de red | P1 | M3, M4 |
| M7 | Observabilidad de extremo a extremo | P1 | M3 |
| M8 | CI como puerta obligatoria | P0 | M1-M4; integra M5-M7 por perfiles |
 |

P0 es necesario para afirmar las garantías funcionales y de consistencia de la entrega. P1 forma
parte del diseño objetivo; sus campañas extensas pueden ser programadas/manuales, pero sus smokes
y verificaciones deterministas deben quedar automatizados donde se indica.

## M0 — Evidencia reproducible y trazabilidad honesta

### Features/TODO

- [x] Crear entradas estables desde el root para `quick`, `full`, `contracts`, `acceptance`,
  `concurrency`, `property`, `fuzz`, `constrained`, `chaos` y limpieza. Deben tener equivalentes
  claros para Windows y Linux y ser las mismas entradas invocadas por GitHub Actions.
- [x] Hacer que cada suite produzca reportes bajo una ruta conocida e incluya versión de Java,
  Maven, Docker, imágenes, semilla, configuración de fallos y límites efectivos cuando apliquen.
- [x] Separar en la matriz de trazabilidad las columnas **diseño**, **implementación** y **evidencia**;
  enlazar cada requisito a una prueba, workflow o TODO concreto.
- [x] Registrar explícitamente pruebas omitidas; una puerta obligatoria debe fallar si no descubre
  las pruebas esperadas.

### Verificación automática

- Ejecutar las entradas en Windows y en `ubuntu-latest`; ambas deben seleccionar las mismas suites.
- Comprobar que un test fallido, uno omitido sin justificación y una suite no descubierta hacen
  fallar la entrada correspondiente.
- Validar que los reportes se generan también ante fallo y no contienen secretos.

### Verificación manual

- Comparar los comandos documentados con los usados por los workflows, sin lógica de pruebas
  duplicada únicamente en YAML.
- Revisar una muestra de reportes fallidos para confirmar que permiten reproducir el problema.

### Criterio de cierre

Un desarrollador o agente nuevo puede ejecutar cada puerta desde el root y obtener el mismo alcance
que CI, sin depender de scripts locales no versionados.

## M1 — Contratos y comportamiento HTTP completo

### Features/TODO

- [x] Validar los dos OpenAPI y AsyncAPI completos contra sus metaschemas, no solo nombres de paths,
  mensajes y campos comunes.
- [x] Agregar validación de compatibilidad para todos los payloads de eventos, enums, formatos UUID,
  versiones, campos requeridos y `additionalProperties` según el contrato aceptado.
- [x] Crear una colección Bruno versionada con todos los endpoints, errores y los flujos
  `PENDING -> CONFIRMED`, `PENDING -> REJECTED` y cancelación hasta compensación `COMPLETED`.
- [x] Comprobar respuestas reales contra OpenAPI, incluidos Problem Details, `Location`,
  `X-Correlation-Id`, `Retry-After`, content type y cuerpos de replay.
- [x] Implementar y documentar límites explícitos de tamaño de request/mensaje y timeouts aceptados.
  Los valores deben coincidir con OpenAPI, configuración de Spring y broker.

### Verificación automática

- Linter/metaschema de OpenAPI y AsyncAPI sobre cada cambio de contrato.
- Tests positivos y negativos por endpoint: IDs, cantidades, body vacío/malformado, campos
  desconocidos, longitudes máximas, `404`, `409`, `503` y Problem Details estable.
- Tests observados que validen status, headers y body de cada operación contra OpenAPI.
- Bruno CLI contra Compose base, incluyendo dos ejecuciones con las mismas claves para demostrar
  replay y consulta del estado terminal.

### Verificación manual

- Importar la colección Bruno y ejecutar una vez el flujo completo desde un entorno limpio.
- Revisar que los ejemplos de README y `EVENTS-AND-RACES.md` siguen siendo solicitudes válidas y
  que no documentan respuestas distintas de las observadas.

### Criterio de cierre

Todo endpoint y evento aceptado tiene schema válido, ejemplo ejecutable y al menos una prueba
positiva y una negativa; CI detecta cualquier divergencia entre contrato e implementación.

## M2 — Idempotencia y persistencia bajo fallos

### Features/TODO

- [x] Extender `http_idempotency` mediante migración hacia adelante para persistir los headers de
  respuesta que deban reproducirse. El replay debe conservar status, body y headers históricos.
- [x] Completar la política de idempotencia: misma clave/payload, misma clave/payload distinto,
  operaciones diferentes, errores de negocio persistibles y fallos técnicos no persistibles.
- [x] Introducir puntos de sincronización solo para tests que permitan provocar timeout al esperar
  la identidad o el lock; responder `503` con `Retry-After` y permitir reusar la misma clave.
- [x] Cubrir explícitamente Flyway desde base vacía y actualización desde la versión anterior.
- [x] Completar pruebas de constraints: `onHand >= reserved >= 0`, límites/overflow, SKU único,
  `movementId` global y unicidad de líneas por producto.
- [x] Implementar retry acotado de deadlock/serialization donde la clasificación aceptada lo
  requiera; no reintentar errores permanentes.

### Verificación automática

- Unit tests del hash canónico y de la matriz completa de política HTTP.
- Integration tests con 100 requests simultáneos de la misma clave: una identidad, un efecto y un
  evento lógico.
- Tests que reproduzcan `404`/`409`, cambien luego el estado y confirmen que el replay mantiene la
  respuesta histórica.
- Test de timeout: primer intento `503`, ninguna fila definitiva, reintento posterior exitoso con
  la misma clave.
- Flyway en PostgreSQL vacío y upgrade con datos; consultas de constraints deben fallar sin dejar
  stock, movimiento u outbox parcial.

### Verificación manual

- Inspeccionar una fila de idempotencia sin exponer la clave en logs y comparar status/body/headers
  con la respuesta original y el replay.
- Revisar el plan de índices y locks para las consultas críticas con un volumen representativo.

### Criterio de cierre

Cada escritura HTTP es exactamente una vez a nivel de efecto de negocio bajo replay y concurrencia;
los fallos técnicos no envenenan la identidad y todas las mutaciones conservan sus constraints.

## M3 — Entrega AMQP demostrable y replay seguro

### Features/TODO

- [x] Crear failpoints deterministas de test antes/después de: commit de negocio, publisher confirm,
  actualización de outbox, commit del consumidor, publicación a retry/DLQ y ACK del original.
- [x] Completar la propagación de `traceparent`, correlación y causación en headers AMQP al publicar
  desde outbox; las transferencias a retry/DLQ deben conservarlos junto con `eventId` y payload.
- [x] Versionar una herramienta y un runbook de DLQ que validen el mensaje, conserven `eventId`,
  publiquen con confirm, comprueben convergencia y solo después retiren la copia original.
- [x] Hacer explícita y testeable la clasificación retryable/permanent, incluidos timeout,
  conexión, deadlock, payload inválido, versión incompatible e invariante imposible.
- [x] Verificar programáticamente topología, bindings, quorum queues, TTL 1/5/30, overflow,
  dead-letter strategy, delivery limit y ruta segura a DLQ.

### Verificación automática

- Caída después de confirm y antes de marcar outbox: republicación con el mismo `eventId`, un solo
  efecto en el consumidor.
- Caída después de commit y antes de ACK: redelivery, inbox única y un solo efecto/outbox derivado.
- Return, NACK, timeout de confirm y lease vencida: el outbox queda pendiente y puede recuperarse.
- Fallo al transferir a retry/DLQ: no ACK del original; duplicados posteriores siguen siendo seguros.
- Recorrido completo por los tres retries hasta DLQ, comprobando contador, causa, cola original,
  headers de contexto y ausencia de pérdida silenciosa.
- Replay de DLQ repetido: convergencia y un solo efecto lógico.

### Verificación manual

- Ejecutar el runbook sobre un mensaje de prueba, observar cada confirmación y validar el estado por
  API; no editar tablas de dominio manualmente.
- Inspeccionar en RabbitMQ Management la topología efectiva y contrastarla con los assertions del
  test automatizado.

### Criterio de cierre

Todas las ventanas críticas tienen una prueba determinista: puede haber redelivery, pero no pérdida
silenciosa, doble efecto ni marcado prematuro de outbox.

## M4 — Carreras, invariantes y múltiples réplicas

### Features/TODO

- [ ] Crear un verificador de invariantes aislado para tests que calcule movimientos efectivos,
  reservas activas, stock y correspondencia Order/Reservation después de drenar mensajes.
- [ ] Convertir las carreras restantes en tests deterministas con barreras, no en pruebas que
  dependan únicamente de probabilidad o tiempos.
- [ ] Versionar k6 y un arnés para dos o más réplicas de cada servicio; debe dirigir tráfico a todas
  las réplicas y registrar cuáles atendieron solicitudes.
- [ ] Cubrir los doce escenarios de concurrencia enumerados en `TEST-STRATEGY.md`, incluidos pedidos
  multítem en orden inverso, última unidad, recuento/reposición/reserva/liberación y la carrera entre
  rechazo y cancelación.

### Verificación automática

- Stock 10 y 100 pedidos concurrentes: exactamente 10 confirmados, 90 rechazados y disponible 0.
- Un evento entregado 100 veces: una reserva o liberación; una reposición repetida 100 veces con
  claves HTTP diferentes: un solo movimiento.
- Reserva/cancelación en ambas intercalaciones: `CANCELLED/COMPLETED` y stock final igual al inicial.
- Pedidos multítem con orden inverso: sin deadlock permanente y sin reserva parcial.
- Dos recuentos con una versión: un éxito, un `409`; replay del ganador devuelve su snapshot original.
- Mezcla concurrente de mutaciones: siempre `onHand >= reserved >= 0`, sin reservas huérfanas.
- k6 multirréplica ejecuta un smoke corto en PR y conserva resumen más verificación final.

### Verificación manual

- Confirmar en logs/resultados que cada réplica procesó trabajo; una sola resolución DNS no basta.
- Ante un fallo, revisar historial de operaciones y convertir la intercalación mínima en una prueba
  determinista de regresión.

### Criterio de cierre

Los doce escenarios pasan repetidamente con PostgreSQL/RabbitMQ reales y múltiples procesos; el
verificador independiente confirma invariantes y convergencia, no solo códigos HTTP.

## M5 — Property tests y fuzzing reproducible

### Features/TODO

- [ ] Fijar versiones compatibles con Java 26 de jqwik y Jazzer antes de activarlos.
- [ ] Agregar property tests rápidos para cantidades límite, stock, transiciones, duplicados,
  desorden y lista completa de faltantes; ejecutarlos en PR.
- [ ] Crear un generador de escenarios distribuidos con semilla, historial de operaciones y
  reducción del caso fallido.
- [ ] Agregar fuzzing guiado por cobertura para parsers/envelopes/validadores puros, sin levantar
  infraestructura por iteración.
- [ ] Mantener un corpus versionado de regresiones; toda semilla que encuentre un defecto debe
  transformarse en una prueba determinista antes de cerrar el defecto.

### Verificación automática

- Perfil rápido con semillas fijas y presupuesto acotado en cada PR.
- Workflow manual/programado que conserve semilla, caso reducido, versiones y artefactos al fallar.
- Propiedades mínimas: no negativos, reserva todo-o-nada, movimiento único, `CANCELLED` terminal,
  evento repetido sin nuevo efecto y convergencia sin reserva huérfana.

### Verificación manual

- Reejecutar una semilla guardada y comprobar que reproduce el historial; si el scheduler no es
  reproducible, convertir la intercalación a barreras controladas.
- Revisar periódicamente el corpus para evitar casos redundantes sin eliminar regresiones útiles.

### Criterio de cierre

Cada fallo aleatorio deja evidencia reproducible y alimenta la suite obligatoria; nunca se reporta
solo “falló alguna vez”.

## M6 — Recursos limitados y caos de red

### Features/TODO

- [ ] Versionar el arnés de `constrained`: carga, inspección de cuotas efectivas, presión observada,
  métricas de reinicio/backlog y verificación posterior de invariantes.
- [ ] Agregar perfil/override `chaos` con Toxiproxy entre servicios y PostgreSQL/RabbitMQ; registrar
  latencia, cortes, timeouts y ancho de banda aplicados.
- [ ] Añadir `tc/netem` solo como campaña opcional en runners Linux que permitan privilegios; no
  hacerlo requisito del desarrollo local normal.
- [ ] Calibrar un smoke corto para PR y separar campañas severas/prolongadas manuales o programadas.

### Verificación automática

- Saturar Order, Inventory y ambos; demostrar presión real y recuperación, no solo límites declarados.
- Acumular outbox con RabbitMQ degradado y comprobar drenaje sin pérdida al recuperarlo.
- Provocar timeout de cada PostgreSQL sin doble efecto y con idempotencia reutilizable.
- Cortar red en ventanas confirm/update y commit/ACK; reiniciar una réplica mientras otra continúa.
- Durante reserva/cancelación/recuento bajo degradación, ejecutar el verificador final de M4.

### Verificación manual

- Revisar límites efectivos, CPU throttling, OOM/reinicios, backlog máximo y tiempo de recuperación.
- Confirmar que una latencia degradada no se confunde con una violación de consistencia.

### Criterio de cierre

El smoke acotado es estable en PR; las campañas extendidas guardan toda la configuración y, tras
retirar el fallo, el sistema converge conservando sus invariantes.

## M7 — Observabilidad de extremo a extremo

### Features/TODO

- [ ] Implementar el perfil Compose `observability` con OpenTelemetry Collector, Prometheus, Tempo,
  Loki y Grafana, configuraciones versionadas, healthchecks, retención corta y límites locales.
- [ ] Incorporar el OpenTelemetry Java agent en las imágenes/configuración del perfil, sin duplicar
  instrumentación con el starter; desactivar métricas del agente y exponer Micrometer/Prometheus.
- [ ] Completar propagación W3C HTTP -> outbox -> headers AMQP -> consumidor y crear un span hijo al
  consumir; conservar continuidad después de retries y replay.
- [ ] Completar logs correlacionados con `service`, `trace_id`, `span_id`, `correlation_id`,
  `causation_id`, `event_id` y `order_id` cuando estén disponibles, sin registrar claves sensibles.
- [ ] Implementar las métricas de negocio/transporte enumeradas en `OBSERVABILITY.md` con labels de
  cardinalidad acotada.
- [ ] Versionar cuatro dashboards y alertas para DLQ, outbox antigua, retries/backlog, consumers,
  confirms, errores, latencia terminal, Hikari y readiness.
- [ ] Mantener health público; limitar métricas y UIs del perfil a redes/puertos de desarrollo
  explícitos y no exponerlas como parte del despliegue base.

### Verificación automática

- Smoke del perfil: los cinco servicios quedan saludables y Prometheus descubre apps/RabbitMQ.
- Crear y cancelar un pedido; consultar Tempo/Loki/Prometheus por API y confirmar una traza continua,
  logs correlacionados y métricas incrementadas una sola vez bajo duplicados.
- Validar que ningún ID de alta cardinalidad aparece como label de métrica.
- Detener el Collector: las operaciones de negocio continúan; buffers son acotados y la telemetría
  se recupera o degrada sin bloquear la aplicación.
- Tests de readiness/liveness con DB o broker temporalmente no disponible según la semántica acordada.

### Verificación manual

- Desde Grafana, partir de un pedido y navegar métrica -> traza -> logs hasta explicar confirmación,
  rechazo, retry o cancelación.
- Disparar cada alerta con datos sintéticos y revisar umbral, duración, mensaje y enlace de diagnóstico.

### Criterio de cierre

Las cuatro preguntas de `OBSERVABILITY.md` pueden responderse desde el stack sin consultar bases
manualmente, y la indisponibilidad del stack nunca bloquea negocio.

## M8 - CI como puerta obligatoria para cambios

### Features/TODO

- [ ] Verificar una ejecución real del workflow actual en GitHub; corregir diferencias del runner y
  actualizar la afirmación de “no ejecutado remotamente” solo con un enlace/evidencia de run.
- [ ] Separar o identificar claramente `static-checks`, `unit`, `integration`, `container`, `e2e`,
  `concurrency-smoke` y un `quality-gate` agregador.
- [ ] Mantener Checkstyle como linter de warnings y la revisión de formato no obligatoria, conforme a
  la guía de estilo; las demás puertas funcionales sí deben bloquear cuando fallen.
- [ ] Incorporar, cuando exista una versión compatible con Java 26, análisis estático de miembros y
  ramas no utilizados. Empezar como warning, revisar falsos positivos y eliminar código solo después
  de contrastarlo con los contratos y los hitos futuros de este roadmap.
- [ ] Añadir validadores de contratos, Bruno, k6 multirréplica, constraints de recursos y las pruebas
  deterministas de mensajería/concurrencia de M1-M4.
- [ ] Agregar workflows manuales/programados para fuzzing, presión, caos y resiliencia extendida.
- [ ] Subir únicamente reportes diagnósticos útiles con retención acotada; incluir logs Compose,
  resultados k6, semillas/fallos y cobertura sin secretos.
- [ ] Configurar protección de `main`: PR obligatorio, `quality-gate` requerido y actualizado,
  conversaciones resueltas, sin force-push/borrado y revisión adicional de contratos, migraciones y
  workflows.

### Verificación automática

- PR de prueba con un fallo en cada job: el agregador debe bloquear ante `failure`, `cancelled` o
  `skipped` de una puerta requerida.
- PR sin tests de integración descubiertos: debe fallar, no pasar silenciosamente.
- Comparar comandos y versiones de herramientas entre ejecución local y Actions.
- Ejecutar dos veces `demo-data`; la segunda corrida no crea efectos adicionales.

### Verificación manual

- Revisar permisos mínimos del `GITHUB_TOKEN`, acciones fijadas por SHA, cache y ausencia de secretos
  en artefactos/logs.
- Confirmar en la configuración de GitHub que las reglas de rama apuntan al nombre estable del
  agregador y no pueden omitirse por filtros de paths.

### Criterio de cierre

Una ejecución verde enlazada demuestra todas las puertas P0 en GitHub y `main` no acepta cambios que
las omitan o fallen.

## Definition of Done transversal

Un TODO solo se marca completado cuando:

1. El contrato o criterio de aceptación fue convertido primero en una prueba que falló por la razón
   esperada, salvo documentación/formato puramente declarativos.
2. La implementación mínima y su refactor mantienen unit, integración y puertas específicas en verde.
3. Se ejecutaron las mismas puertas relevantes de CI y se reportaron comando, resultado y cualquier
   verificación no ejecutada.
4. No se desactivaron pruebas, redujeron umbrales ni ocultaron fallos.
5. Contratos, migraciones, Compose, runbooks y documentación quedaron sincronizados.
6. Toda prueba aleatoria conserva semilla/corpus y toda prueba de fallo conserva la inyección exacta.
7. No quedan datos o artefactos de test con secretos y los cambios no introducen dependencias entre
   dominios ni acceso cruzado a bases.

## Evidencia requerida para declarar la implementación completa

- Enlace a una ejecución verde de `quality-gate` en un pull request y en `main`.
- Reportes de unit/integration, cobertura y validación de contratos sin tests omitidos.
- Resultado Bruno, k6 multirréplica y verificador de invariantes.
- Resultados de las ventanas deterministas de outbox/inbox/retry y replay de DLQ.
- Smoke de recursos limitados y de pérdida temporal de telemetría; configuración efectiva adjunta.
- Capturas o consultas exportables de dashboards, alertas y una traza distribuida completa.
- Digests GHCR, SBOM, resultados de escaneo y attestations de las dos imágenes.
