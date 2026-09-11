# Replay controlado de DLQ

La herramienta opera sobre **un mensaje en la cabeza** de `order.dlq` o `inventory.dlq`.
No purga colas, no cambia el payload y no consulta bases de datos. Corregir primero la causa
del fallo; un mensaje que incumple AsyncAPI debe permanecer en DLQ para revisión, no editarse
silenciosamente ni renumerarse para eludir inbox.

## Procedimiento

1. Iniciar el entorno con `docker compose up --build --wait`.
2. En RabbitMQ Management (`http://localhost:15672`), inspeccionar la cabeza de la DLQ con
   **requeue activado**. Anotar `eventId`, `aggregateId`, causa y cola original. No usar purge ni
   ACK manual. La credencial procede de `.env`; no copiarla a tickets o reportes.
3. Consultar `GET /orders/{aggregateId}`. Elegir el estado terminal esperado según el flujo:
   `CONFIRMED`, `REJECTED` o `CANCELLED` (este último exige compensación `COMPLETED`).
4. Ejecutar desde el root, sustituyendo los tres valores de ejemplo:

```text
docker compose --profile operations run --rm --no-deps dlq-replay inventory EVENT_UUID http://order-service:8080/orders/ORDER_UUID CONFIRMED
```

Para resultados de Inventory retenidos en `order.dlq`, usar `order` como primer argumento.
Se utiliza la imagen existente de Order solamente como contenedor del launcher técnico:
**no se inicia Spring ni se accede a su base**. Las credenciales AMQP se heredan del entorno
Compose y nunca se reciben como argumentos.

5. La herramienta valida AsyncAPI, destino e identidad; conserva bytes, `eventId` y contexto,
   reinicia `retry-attempt` a cero y agrega `replayed=true`. Publica con routing obligatorio
   y espera confirm hasta 5 s. Consulta la API hasta 45 s y solo entonces confirma el original.
6. Exigir salida cero y el mensaje `Replay confirmed, terminal state verified, original acknowledged`.
   Consultar nuevamente la API y verificar el stock por la API de Inventory. Guardar IDs,
   estado, hora y resultado, nunca credenciales ni claves HTTP.

Si la cabeza cambió, no coincide el ID, el mensaje es inválido, falla el confirm o no converge,
la salida es no cero y el original se reencola. Una caída posterior al confirm puede dejar dos
copias: esto es intencional; inbox garantiza un solo efecto. Repetir con el **mismo** ID.
Una DLQ vacía devuelve error explícito, no simula un replay exitoso.

## Verificación reproducible

`java scripts/Verify.java concurrency` ejecuta `MessagingIT`: replay retenido hasta convergencia,
replay repetido con inbox única, mensaje inválido conservado, failpoints de commit/confirm/ACK
y recorrido real 1/5/30 hasta DLQ. `ConfirmedPublisherTest` verifica return, NACK y timeout.
El comando Compose puede probarse sin mutar datos usando un ID distinto de la cabeza: debe fallar
y conservar el mensaje. El ejercicio manual con un mensaje real necesita una DLQ de prueba
controlada; nunca provocar un incidente en producción para demostrar este procedimiento.

Referencias: [publisher confirms y acknowledgements](https://www.rabbitmq.com/docs/confirms),
[quorum queues y dead lettering](https://www.rabbitmq.com/docs/quorum-queues).
