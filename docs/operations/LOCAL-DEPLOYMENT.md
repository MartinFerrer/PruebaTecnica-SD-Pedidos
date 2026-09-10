# Despliegue local y datos predeterminados

Estado: **Base/default y overrides de recursos/réplicas implementados; perfiles opcionales pendientes**

## Uso disponible actualmente

Con Docker Desktop en modo Linux, copiar `.env.example` a `.env`, establecer contraseñas locales y ejecutar `docker compose up --build --wait --wait-timeout 180`. Las APIs escuchan en `127.0.0.1:8081` (Order) y `127.0.0.1:8082` (Inventory); RabbitMQ Management en `127.0.0.1:15672`. Las bases no publican puertos al host.

La verificación mínima multiplataforma es `docker compose config --quiet` seguido de `docker compose up --build --wait --wait-timeout 180`. Las pruebas de carga y recursos limitados usadas durante el bootstrap fueron scripts locales no versionados; este despliegue base no pretende demostrar agotamiento de memoria ni degradación de red.

## Verificación rápida desde Postman

Después de que los servicios estén saludables, se pueden probar directamente:

- `GET http://127.0.0.1:8082/products` para listar productos.
- `GET http://127.0.0.1:8081/orders` para listar órdenes.
- `GET http://127.0.0.1:8082/products/{productId}/stock` para un producto concreto.
- `GET http://127.0.0.1:8081/orders/{orderId}` para una orden concreta.

Primero cree un producto u orden con su endpoint `POST` y conserve el UUID de la respuesta. Los
segmentos `{productId}` y `{orderId}` no aceptan valores numéricos como `0`; enviar `0` produce
`400 INVALID_REQUEST` porque los identificadores son UUID.

`docker compose down` detiene el entorno y conserva volúmenes. No usar opciones de eliminación de volúmenes si se desean conservar los datos.

Las secciones de `demo-data`, `observability` y `chaos` siguientes describen el diseño pendiente: esos perfiles todavía no están disponibles. Ver [estado de verificación](../testing/IMPLEMENTATION-VERIFICATION.md).

## Contenedores base

El despliegue normal tendrá cinco contenedores:

| Contenedor | Responsabilidad | Acceso a datos |
|---|---|---|
| `order-service` | API y eventos de pedidos | Solo `order-db` y RabbitMQ |
| `inventory-service` | API, reservas y eventos de stock | Solo `inventory-db` y RabbitMQ |
| `order-db` | Datos privados de Order | Red privada de Order |
| `inventory-db` | Datos privados de Inventory | Red privada de Inventory |
| `rabbitmq` | Transporte asíncrono | Accesible por ambos servicios |

Los volúmenes, usuarios y credenciales de las bases son diferentes. No se montan scripts o volúmenes de una base en la otra.

## Perfiles y overrides Compose

| Modo | Propósito |
|---|---|
| base/default | Ejecutar los cinco contenedores funcionales. |
| `demo-data` | Cargar un escenario reproducible mediante APIs públicas. |
| `observability` | Agregar Collector, Prometheus, Tempo, Loki y Grafana. |
| override `constrained` | Aplicar cuotas reducidas de CPU, memoria y PIDs a los servicios existentes. |
| perfil + override `chaos` | Agregar Toxiproxy y cambiar conexiones seleccionadas para degradación controlada. |

Los modos pueden combinarse, excepto cuando una prueba define un presupuesto que excluye observabilidad. Un perfil activa servicios; no cambia por sí solo las cuotas ni las URLs de servicios existentes. Esas modificaciones se definen en archivos Compose de override.

## Carga opcional de datos

`demo-data` será un contenedor one-shot. Espera readiness, invoca únicamente los endpoints públicos y termina con código distinto de cero si el escenario no converge.

No escribe SQL directamente porque eso omitiría validaciones, idempotencia, movimientos de stock, outbox y telemetría.

Dataset propuesto:

| SKU | Nombre | Stock inicial |
|---|---|---:|
| `DEMO-KEYBOARD` | Mechanical Keyboard | 20 |
| `DEMO-MOUSE` | Wireless Mouse | 50 |
| `DEMO-MONITOR` | 27-inch Monitor | 5 |
| `DEMO-EMPTY` | Out-of-stock Product | 0 |

Después del alta, el seed runner aplica una reposición demostrativa de +5 a `DEMO-KEYBOARD` con `movementId` estable y luego crea:

- un pedido que converge a `CONFIRMED`;
- un pedido multítem que converge a `REJECTED` con dos faltantes;
- un pedido confirmado que luego converge a `CANCELLED` y stock liberado.

Cada llamada usa una `Idempotency-Key` estable y específica de la versión del dataset. El runner captura los `productId` de las respuestas/replays, espera estados con polling acotado y comprueba las poscondiciones del escenario. Ejecutarlo otra vez obtiene replays de las mismas operaciones y no suma stock ni crea pedidos extra; las identidades no expiran en este alcance. El seed no usa reconteos para restaurar balances y no modifica pedidos ajenos.

La prueba de saldos exactos del dataset se ejecuta en volúmenes de prueba vacíos y sin carga externa. Tras interacción manual, volver a ejecutar el seed solo verifica identidades y estados de sus pedidos: no exige los saldos iniciales ni deshace cambios del desarrollador. El arnés de pruebas verifica separadamente dos ejecuciones, también concurrentes, sin duplicar cantidades. Un dataset nuevo debe tener una estrategia explícita de versionado; cambiar payloads con las mismas claves es un conflicto, no un mecanismo de actualización.

## Formas de inicio previstas

Cuando exista el Compose:

- sistema vacío: `docker compose up --wait`;
- sistema con datos: primero `docker compose up --wait`, después `docker compose --profile demo-data run --rm demo-data` y comprobar su código de salida;
- sistema observable: `docker compose --profile observability up --wait`;
- prueba limitada: archivo base más override `constrained` y, cuando corresponda, override/perfil `chaos`.

El contenedor de carga es one-shot: no se incluye como servicio permanente en el `up --wait` del sistema base. Los comandos son contratos de operación futura; los archivos ejecutables se crearán en implementación.

Los comandos de Compose son iguales en PowerShell, Command Prompt, Bash, macOS, Linux y Windows. La limpieza distingue entre `docker compose down` (conserva volúmenes) y la eliminación explícita de volúmenes cuando se quiera reiniciar el entorno.

## Múltiples réplicas y límites efectivos

Los servicios escalables no fijarán `container_name` ni un mismo puerto de host por réplica. El arnés de pruebas accede a puertos descubiertos o direcciones internas de cada réplica y distribuye peticiones explícitamente, comprobando que todas atiendan trabajo; resolver un nombre DNS una sola vez no demuestra reparto. No se añade un gateway por este motivo.

El runner verifica cuotas efectivas mediante inspección Docker y registra CPU throttling, memoria/OOM, reinicios y backlog. La JVM y los pools tienen presupuestos compatibles con el límite del contenedor. Toxiproxy se introduce solo en los caminos seleccionados y el test guarda la configuración de fallos. Tras retirar restricciones/fallos transitorios, se comprueban invariantes y convergencia con timeout; no basta que k6 reciba respuestas HTTP.

## Limitaciones declaradas

- RabbitMQ local usa quorum queues de un miembro y no demuestra alta disponibilidad de un cluster; conservar volúmenes y probar reinicios no demuestra tolerancia a pérdida del nodo/disco.
- Compose demuestra separación y escalado de procesos, pero no sustituye un orquestador de producción.
- Las credenciales de desarrollo no se reutilizan en otro entorno.
- Los datos predeterminados son solo de demostración y no forman parte de migraciones productivas.
