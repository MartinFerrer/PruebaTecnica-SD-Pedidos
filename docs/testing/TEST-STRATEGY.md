# Estrategia de pruebas

Estado: **Implementación incremental en curso**

## Objetivo

Las pruebas deben demostrar las propiedades difíciles del sistema: no vender stock inexistente, idempotencia, tolerancia a redelivery, convergencia ante cancelación y recuperación de fallos. La cobertura numérica es una señal secundaria.

## Pirámide y herramientas

| Nivel | Herramientas definidas | Qué valida |
|---|---|---|
| Dominio unitario | JUnit + AssertJ | Invariantes y tablas completas de transición. |
| Aplicación unitaria | JUnit + dobles en puertos | Casos de uso, efectos solicitados y errores sin Spring. |
| Arquitectura | ArchUnit | Dependencias hexagonales y separación entre servicios. |
| Persistencia/mensajería | Spring test slices o contexto acotado + Testcontainers | Mapeo, Flyway, constraints, locks, inbox/outbox y RabbitMQ. |
| Servicio | `@SpringBootTest`, Testcontainers, REST Assured, Awaitility | API real, transacciones y procesamiento asíncrono. |
| Contrato | Validación OpenAPI/AsyncAPI y tests de compatibilidad | Requests, responses, envelopes y evolución de esquemas. |
| Aceptación | Bruno CLI contra Docker Compose | Endpoints mínimos y flujos completos. |
| Concurrencia | JUnit determinista + PostgreSQL real + k6 | Carreras reproducibles y carga multicliente/multiinstancia. |
| Resiliencia | Testcontainers/Toxiproxy y fallos controlados | DB/broker temporalmente inaccesible y crash entre commit/ACK. |
| Propiedades/fuzzing | jqwik + Jazzer opcional + generador de escenarios con semilla | Cantidades, secuencias, duplicados, desorden y payloads inesperados. |
| Presión de recursos | k6 + límites Docker + Toxiproxy; `tc/netem` opcional en Linux | Saturación de CPU/memoria y degradación/cortes de red sin perder consistencia. |

No se usa H2: su locking, tipos y SQL difieren de PostgreSQL precisamente en los puntos que se evalúan.

### Estado de implementación

La primera capa de la estrategia ya está activa:

- unit tests de dominio/aplicación y reglas ArchUnit por servicio y para `shared-library`;
- integration tests con PostgreSQL y RabbitMQ reales mediante Testcontainers;
- pruebas de rollback, inbox/outbox, duplicados, mensajes inválidos y carreras de cancelación;
- carreras concurrentes para reposición repetida, unicidad global de `movementId` y dos reconteos con el mismo `expectedVersion`;
- checks estructurales iniciales de OpenAPI/AsyncAPI, incluido el path singular de reposición;
- aceptación Compose mediante `demo-data`, ejecutada dos veces por CI para probar replay idempotente.

La validación completa contra metaschemas, Bruno, k6 multirréplica, Toxiproxy y fuzzing con semilla continúan como incrementos siguientes; los checks estructurales actuales no se presentan como validación exhaustiva del contrato.

### Configuración de ejecución

Maven Surefire ejecuta `*Test` (unit, ArchUnit y propiedades rápidas); Failsafe ejecuta `*IT` en `integration-test` y comprueba resultados en `verify`. Los módulos con suites obligatorias fallan si no se descubren pruebas. No usar `-DskipTests`, exclusiones silenciosas ni perfiles que hagan pasar CI sin integración. El reactor raíz solo agrega módulos y configuración común: no necesita pruebas vacías.

JaCoCo aplica cobertura a dominio/aplicación y publica reportes; no se excluye lógica para alcanzar el umbral. Se fija la compatibilidad con Java 26 de plugins, Mockito, jqwik y Jazzer antes de usarlos. ArchUnit comprueba dominio/aplicación sin Spring/JPA/AMQP, adaptadores dependientes de puertos y cero referencias Java entre servicios. Tests de rollback verifican que el decorador transaccional envuelva también inbox, idempotencia y outbox usando la misma conexión por servicio.

## TDD

Cada historia se divide en incrementos verticales pequeños:

1. Ejemplo/criterio de aceptación y contrato.
2. Prueba de dominio fallando.
3. Mínima implementación del dominio.
4. Prueba del caso de uso fallando y adaptación mínima.
5. Prueba de integración con PostgreSQL/RabbitMQ real.
6. Aceptación end-to-end cuando el incremento cruza servicios.
7. Refactor y ejecución de puertas completas.

El agente debe conservar evidencia de que la prueba nueva falló por el motivo esperado antes de la implementación. No se acepta una prueba escrita después que nunca haya demostrado detectar la ausencia del comportamiento.

## Suites previstas

### Unit

- Creación válida e inválida de Order/Product.
- Matriz de transiciones de Order, incluyendo eventos tardíos.
- Matriz de Reservation, duplicados, versiones viejas y cancelación anticipada.
- Reserva multítem todo-o-nada.
- Cálculo `available = onHand - reserved` y rechazo de una actualización `onHand < reserved`.
- Reposición positiva, versión en cada mutación, reconteo obsoleto/no-op, cantidades límite y overflow sin wraparound.
- Identidad de movimiento reutilizada con producto, cantidad o razón diferentes; rechazo de líneas de pedido con `productId` repetido.
- Cálculo completo de todos los `unavailableItems`, incluidos productos inexistentes.
- Hash y política de idempotencia HTTP.
- Clasificación de errores retryable/permanent.

### Integration por servicio

- Migraciones desde base vacía.
- Restricción de stock no negativo.
- Dos transacciones intentan reservar el último stock.
- Actualización de stock compite con reservas activas sin violar `onHand >= reserved`.
- Reposición, reserva, liberación y reconteo comparten lock de producto; `expectedVersion` se valida después de adquirirlo.
- Unicidad global de `movementId`, incluidas carreras con productos distintos: el perdedor revierte stock/outbox antes de resolver replay/conflicto.
- Locks adquiridos en orden estable y retry de deadlock.
- Inbox y efecto de negocio en la misma transacción.
- Outbox y agregado en la misma transacción.
- Publisher confirm; caída antes de marcar produce publicación duplicada con el mismo `eventId`.
- Consumidor cae después del commit y antes de ACK; la redelivery no repite efecto.
- Retry y DLQ conservan metadatos.
- Mensaje no enrutable, NACK, confirm incierto y lease de relay vencida no marcan outbox como publicado.
- Transferir a retry/DLQ no confirma el original hasta confirmar el destino; caída entre ambas confirmaciones produce duplicado seguro.
- Quorum queues con TTL y política at-least-once/reject-publish: indisponibilidad de la cola destino no pierde el mensaje; verificar DLX/bindings y delivery-limit/ruta segura de las colas de entrada.
- Cancelación después de rechazo en Inventory pero antes de rechazo en Order emite `StockReleased` sin cantidades; los resultados tardíos no revierten `COMPLETED`.

### API y contrato

- `POST /orders` devuelve `202`, `Location` y el mismo resultado al repetir clave/payload.
- `GET /orders/{id}` devuelve todos los `unavailableItems` cuando el pedido termina `REJECTED`.
- `POST /products` crea stock inicial una sola vez y publica `ProductStockCreated`.
- `POST /products/{id}/restock` suma una vez por `movementId` y publica un único `ProductStockReplenished`, incluso si se repite con otra clave HTTP.
- `PUT /products` reemplaza el valor absoluto con `expectedVersion` válido y publica `ProductStockUpdated` solo si cambia cantidades.
- PUT obsoleto devuelve `409`; GET devuelve la versión vigente; replay de una operación confirmada reproduce su respuesta histórica antes de revalidar versiones.
- `PUT /products` devuelve `409` si el nuevo stock físico queda bajo lo reservado.
- Clave igual con payload diferente devuelve `409`.
- Los rechazos de negocio admitidos (`404`/`409`) se reproducen sin cambios; un timeout de lock devuelve `503` con `Retry-After` y permite reintentar la misma clave sin almacenar el fallo transitorio.
- Consultas inexistentes devuelven Problem Details consistente.
- Cancelación repetida conserva el resultado.
- Validación de cantidades, IDs y límites de request.
- OpenAPI coincide con respuestas observadas.

### Concurrencia y carreras

Pruebas deterministas con barreras/latches coordinan el instante de las transacciones; k6 mide la solución completa.

Escenarios de aceptación:

1. Stock 10, 100 pedidos concurrentes de una unidad, sin reposiciones ni cancelaciones: tras procesar todos, exactamente 10 quedan `CONFIRMED`; disponible termina en 0.
2. 100 solicitudes simultáneas con una misma `Idempotency-Key`: existe un pedido y un evento lógico.
3. El mismo evento se entrega 100 veces: una reserva o una liberación.
4. Reserva y cancelación aceptada se intercalan en ambos órdenes: Order termina `CANCELLED` y compensación `COMPLETED`; sin otros movimientos, el stock final coincide con el inicial.
5. Pedidos multítem en orden inverso: sin deadlock permanente y sin reserva parcial.
6. Dos o más réplicas de cada servicio consumen durante la prueba.
7. Reinicio de consumidor en la ventana commit/ACK: convergencia sin segundo efecto.
8. Actualizaciones concurrentes de stock, reservas y cancelaciones conservan `onHand >= reserved >= 0`.
9. +5 y +7 sobre 20, repartidos entre réplicas, dejan 32; 100 repeticiones del movimiento +5, con claves HTTP distintas, no lo incrementan de nuevo.
10. GET 20/v8, reposición +5 -> 25/v9, PUT con v8 -> `409`; inversión de ese orden respeta la suma posterior al reconteo. Repetir también con reserva/liberación concurrentes.
11. Dos reconteos con la misma versión leída: solo uno puede cambiar cantidades; el otro obtiene `409`. Repetir la clave exitosa devuelve el snapshot original.
12. Cancelar compite con consumir rechazo: si rechazo gana en Order, cancelación `409`; si cancelación fue aceptada, convergencia a `CANCELLED/COMPLETED` aunque Inventory ya haya rechazado.

El verificador usa datos aislados y cuenta movimientos efectivos, no intentos HTTP. Comprueba `reserved = suma de reservation_items de reservas activas` y `onHand = suma de deltas físicos de movimientos confirmados`, incluido el delta de cada reconteo. Después de drenar mensajes y completar replays necesarios, contrasta estados de Order y Reservation para detectar reservas huérfanas. Las lecturas directas de ambas bases están permitidas solo al arnés de pruebas, nunca a los servicios.

Los assertions asíncronos usan Awaitility con timeout y polling; no usan `sleep` fijo.

## Fuzzing y pruebas basadas en propiedades

El fuzzing extensivo es opcional y se ejecuta mediante un perfil separado, de forma programada o manual. No reemplaza las pruebas deterministas.

- **jqwik:** propiedades rápidas del dominio y de la máquina de estados. Genera cantidades límite, pedidos multítem, secuencias de crear/reservar/cancelar/actualizar y reduce el caso al ejemplo mínimo cuando falla.
- **Generador con semilla:** produce duplicados, reordenamientos, demoras y combinaciones de estados para integración, incluidas reposiciones y reconteos. Guarda semilla, historial de operaciones, fallos inyectados y versiones/estados observados. La semilla sola no reproduce el scheduler distribuido: se reduce el historial y se convierte la intercalación fallida en una regresión con barreras controladas.
- **Jazzer:** perfil opcional de fuzzing guiado por cobertura para parsers/envelopes, validadores y transiciones puras. No se conecta a infraestructura en cada iteración.
- **k6 aleatorio:** combina cantidades, tiempos entre requests y distribución de endpoints para carga end-to-end, siempre con semilla conocida y un verificador final determinista.

Propiedades invariantes:

- `available = onHand - reserved` y ninguno de los tres valores es negativo.
- Una reserva activa contiene todas las líneas; un rechazo puede tener registro de Reservation pero no cantidades reservadas.
- Cada movimiento de reposición afecta una sola vez al saldo y ningún reconteo obsoleto lo sobrescribe.
- Un evento repetido no cambia el resultado después de la primera aplicación.
- `CANCELLED` es terminal.
- Tras convergencia, toda reserva de un pedido cancelado está liberada o nunca existió.
- La lista de faltantes contiene exactamente todos los ítems que no satisfacen la cantidad solicitada en el snapshot bloqueado.

El perfil rápido usa pocos ejemplos y puede ejecutarse en PR. El perfil extensivo aumenta iteraciones/tiempo y se reserva para ejecución nocturna o manual.

## Pruebas con bajos recursos y red degradada

k6 por sí solo no es suficiente: genera presión y mide respuestas, pero no impone límites de CPU/memoria, no altera la red y no demuestra por sí mismo las invariantes internas.

La estrategia combina:

1. Override Compose `constrained` con cuotas pequeñas de CPU, memoria y PIDs para ambos servicios, RabbitMQ y PostgreSQL; el arnés inspecciona límites efectivos de los contenedores, no solo el YAML.
2. k6 para elevar concurrencia hasta observar saturación, backlog y reintentos.
3. Toxiproxy entre aplicaciones y PostgreSQL/RabbitMQ para latencia, ancho de banda, timeouts y cortes/reconexiones reproducibles.
4. `tc/netem` opcional en runners Linux para jitter y pérdida de paquetes cuando el entorno permita privilegios de red.
5. Reinicio controlado de contenedores para cubrir fallos durante outbox publish y antes del ACK.
6. Verificador posterior que espera convergencia y consulta APIs y datos de test para comprobar conservación de stock, unicidad de efectos e inexistencia de reservas huérfanas.

Escenarios mínimos:

- saturar solo Order, solo Inventory y ambos;
- limitar RabbitMQ hasta acumular outbox y verificar drenaje posterior;
- limitar cada PostgreSQL y provocar timeouts/retries sin doble efecto;
- cortar la red en las ventanas commit/confirm y commit/ACK;
- presión de memoria con reinicio de una réplica mientras otra continúa;
- ejecutar reserva/cancelación/actualización de stock durante la degradación.

La latencia puede incumplir objetivos bajo el perfil limitado; las invariantes de consistencia siempre son obligatorias. El stack de observabilidad se desactiva por defecto en esta prueba para que no consuma el presupuesto, salvo cuando se evalúa telemetría bajo presión.

El smoke obligatorio en PR aplica cuotas calibradas al runner, carga acotada y al menos una interrupción recuperable de red. Guarda CPU throttling, memoria/OOM, reinicios, backlog, configuración efectiva y tiempos de recuperación. No afirmar saturación por declarar una cuota: debe observarse presión real. Si no arranca o no converge en el plazo acotado tras retirar fallos, la prueba falla y conserva diagnóstico. La matriz severa/prolongada es opcional; las ventanas críticas de commit/ACK, confirm y cancelación también tienen tests deterministas obligatorios en integración.

## Datos y aislamiento

- Cada test genera IDs propios y no depende del orden de ejecución.
- Integration tests limpian mediante rollback cuando corresponde o recrean esquema/container.
- Las pruebas que comparten broker usan nombres de cola aislados.
- Seeds mínimos y explícitos; no snapshots opacos de bases.
- El perfil `demo-data` carga seeds mediante APIs públicas y claves idempotentes; una prueba verifica que ejecutarlo dos veces no cambia las cantidades.
- Reloj e IDs son puertos controlables en unit tests.

## Puertas de calidad definidas

- Compilación con warnings relevantes tratados como fallo.
- Formato automático y análisis estático.
- Unit, ArchUnit e integration tests obligatorios.
- Cobertura de domain/application: umbrales iniciales 85 % de líneas y 75 % de ramas; no reducirlos sin aprobación explícita.
- Cero pruebas omitidas sin ticket/justificación.
- Validación de OpenAPI, AsyncAPI y Docker Compose.
- Smoke concurrente y de recursos limitados en cada pull request; estrés extendido programado o manual para evitar CI inestable.
- Property tests rápidos en PR; fuzzing extensivo y caos de red mediante workflows programados/manuales.

`jcstress` solo se añadirá si aparece lógica lock-free dentro de la JVM. No sustituye las pruebas reales contra PostgreSQL y múltiples procesos.

## Comandos reproducibles

La interfaz estable desde el root está en `java scripts/Verify.java`, con wrappers mínimos en
`scripts/verify.sh` y `scripts/verify.cmd`:

- verificación rápida: `java scripts/Verify.java quick`;
- verificación completa: `java scripts/Verify.java full`, incluidos integration tests;
- contratos: `java scripts/Verify.java contracts`;
- aceptación: `java scripts/Verify.java acceptance`;
- concurrencia: `java scripts/Verify.java concurrency`;
- fuzzing extensivo: `java scripts/Verify.java fuzz`, conservando corpus y semillas cuando esté implementado;
- recursos limitados: `java scripts/Verify.java constrained`;
- caos de red: `java scripts/Verify.java chaos`;
- limpieza: `java scripts/Verify.java clean`, detiene Compose sin eliminar volúmenes.

Las suites aún no implementadas terminan con error explícito y un reporte `SUITE_NOT_IMPLEMENTED`.
Esto evita que una puerta pase silenciosamente sin descubrir pruebas.

Los workflows de GitHub invocarán esos mismos comandos; la lógica no se duplicará en YAML.
