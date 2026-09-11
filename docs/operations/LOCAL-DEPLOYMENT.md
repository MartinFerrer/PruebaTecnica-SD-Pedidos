# Despliegue local y datos predeterminados

## Uso del sistema localmente con Docker

Con Docker Desktop en modo Linux, copiar `.env.example` a `.env`, establecer contraseñas locales y ejecutar `docker compose up --build --wait --wait-timeout 180`. Las APIs escuchan en `127.0.0.1:8081` (Order) y `127.0.0.1:8082` (Inventory); RabbitMQ Management en `127.0.0.1:15672`. Las bases no publican puertos al host.

La verificación mínima multiplataforma es `docker compose config --quiet` seguido de `docker compose up --build --wait --wait-timeout 180`. Las pruebas de carga y recursos limitados usadas durante el bootstrap fueron scripts locales no versionados; este despliegue base no pretende demostrar agotamiento de memoria ni degradación de red.

## Verificación rápida desde Postman / otro Endpoint tester

Después de que los servicios estén saludables, se pueden probar directamente:

- `GET http://127.0.0.1:8082/products` para listar productos.
- `GET http://127.0.0.1:8081/orders` para listar órdenes.
- `GET http://127.0.0.1:8082/products/{productId}/stock` para un producto concreto.
- `GET http://127.0.0.1:8081/orders/{orderId}` para una orden concreta.

Primero cree un producto u orden con su endpoint `POST` y conserve el UUID de la respuesta. Los
segmentos `{productId}` y `{orderId}` no aceptan valores numéricos como `0`; enviar `0` produce
`400 INVALID_REQUEST` porque los identificadores son UUID.

`docker compose down` detiene el entorno y conserva volúmenes. No usar opciones de eliminación de volúmenes si se desean conservar los datos.

El perfil `demo-data`, el override `replicas` y los perfiles `chaos` y `observability` están disponibles. Ver [estrategia de pruebas](../testing/TEST-STRATEGY.md).

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

`demo-data` es un contenedor one-shot. Compose espera readiness de ambas APIs antes de iniciarlo; el runner invoca únicamente endpoints públicos y termina con código distinto de cero si el escenario no converge.

No escribe SQL directamente porque eso omitiría validaciones, idempotencia, movimientos de stock, outbox y telemetría.

Dataset propuesto:

| SKU | Nombre | Stock inicial |
|---|---|---:|
| `DEMO-KEYBOARD` | Mechanical Keyboard | 20 |
| `DEMO-MOUSE` | Wireless Mouse | 50 |
| `DEMO-MONITOR` | 27-inch Monitor | 5 |
| `DEMO-EMPTY` | Out-of-stock Product | 0 |

Después del alta, el runner `populate_dummy_data.py` aplica una reposición demostrativa de +5 a `DEMO-KEYBOARD` con `movementId` estable y luego crea:

- un pedido que converge a `CONFIRMED`;
- un pedido multítem que converge a `REJECTED` con dos faltantes;
- un pedido confirmado que luego converge a `CANCELLED` y stock liberado.

Cada llamada usa una `Idempotency-Key` estable y específica de la versión del dataset. El runner captura los `productId` de las respuestas/replays, espera estados con polling acotado y comprueba las poscondiciones del escenario. Ejecutarlo otra vez obtiene replays de las mismas operaciones y no suma stock ni crea pedidos extra; las identidades no expiran en este alcance. El runner no usa reconteos para restaurar balances y no modifica pedidos ajenos.

En modo periódico, `--orders-per-cycle` controla cuántos lotes de tres pedidos se crean por ciclo
(confirmado, cancelado y rechazado). `--interval-seconds` acepta valores decimales y `0` para
ejecutar ciclos sin pausa; cada lote usa claves idempotentes distintas.

La prueba de saldos exactos del dataset se ejecuta en volúmenes de prueba vacíos y sin carga externa. Tras interacción manual, volver a ejecutar el runner solo verifica identidades y estados de sus pedidos: no exige los saldos iniciales ni deshace cambios del desarrollador. CI ejecuta el runner dos veces en secuencia sin duplicar cantidades; la variante concurrente continúa como ampliación de la suite de carreras. Un dataset nuevo debe tener una estrategia explícita de versionado; cambiar payloads con las mismas claves es un conflicto, no un mecanismo de actualización.

## Formas de inicio

- sistema vacío: `docker compose up --wait`;
- sistema con datos: primero `docker compose up --build --wait --wait-timeout 180`, después `docker compose --profile demo-data run --build --rm demo-data` y comprobar su código de salida;
- sistema observable: `docker compose -f compose.yaml -f deploy/compose/observability.yaml --profile observability up --build --wait --wait-timeout 180`;
- prueba limitada: archivo base más override `constrained` y, cuando corresponda, override/perfil `chaos`.
- réplicas y k6: archivo base más `deploy/compose/replicas.yaml`; el runner activa dos réplicas y el servicio k6.
- caos corto: archivo base más `deploy/compose/chaos.yaml`; el runner crea proxies para ambas bases y RabbitMQ.

El contenedor de carga es one-shot: no se incluye como servicio permanente en el `up --wait` del sistema base. CI lo ejecuta dos veces sobre volúmenes limpios para comprobar que el segundo pase reproduce las respuestas sin duplicar efectos.

Los comandos de Compose son iguales en Windows, macOS y Linux cuando se ejecutan desde un shell compatible. La limpieza distingue entre `docker compose down` (conserva volúmenes) y la eliminación explícita de volúmenes cuando se quiera reiniciar el entorno.

## Múltiples réplicas y límites efectivos

Los servicios escalables no fijarán `container_name` ni un mismo puerto de host por réplica. El arnés de pruebas accede a puertos descubiertos o direcciones internas de cada réplica y distribuye peticiones explícitamente, comprobando que todas atiendan trabajo; resolver un nombre DNS una sola vez no demuestra reparto. No se añade un gateway por este motivo.

El runner verifica cuotas efectivas mediante inspección Docker y registra CPU throttling, memoria/OOM, reinicios y backlog. La JVM y los pools tienen presupuestos compatibles con el límite del contenedor. Toxiproxy se introduce solo en los caminos seleccionados y el test guarda la configuración de fallos. Tras retirar restricciones/fallos transitorios, el smoke comprueba replay, estado de colas y recuperación; las invariantes y la convergencia se verifican en la puerta de concurrencia.

## Limitaciones declaradas

- RabbitMQ local usa quorum queues de un miembro y no demuestra alta disponibilidad de un cluster; conservar volúmenes y probar reinicios no demuestra tolerancia a pérdida del nodo/disco.
- Compose demuestra separación y escalado de procesos, pero no sustituye un orquestador de producción.
- Las credenciales de desarrollo no se reutilizan en otro entorno.
- Los datos predeterminados son solo de demostración y no forman parte de migraciones productivas.
