# Reglas del repositorio para agentes

Estas reglas aplican a todo el repositorio.

## Fase actual: IMPLEMENTACION

- La revisión final y el paso de fase están autorizados por el usuario. Las decisiones aceptadas están consolidadas en `docs/architecture/ARCHITECTURE.md` y `docs/architecture/EVENTS-AND-RACES.md`.
- El desarrollo de código está autorizado; mantener los contratos, pruebas y documentación sincronizados con cada entrega.
- Al comenzar el desarrollo, definir contratos y configurar las herramientas de verificación antes de implementar comportamiento, siguiendo TDD.
- Registrar cambios arquitectónicos futuros en los documentos finales; no recrear registros de preguntas resueltas ni ADR vacías.

## Fuentes de verdad

1. La solicitud explícita y más reciente del usuario.
2. Los contratos OpenAPI y AsyncAPI, cuando existan.
3. `docs/architecture/ARCHITECTURE.md` y `docs/architecture/EVENTS-AND-RACES.md`.
4. `docs/architecture/REQUIREMENTS-TRACEABILITY.md`, `docs/testing/TEST-STRATEGY.md` y los documentos de `docs/operations/` y `docs/delivery/`.
5. Este archivo.

Si dos fuentes se contradicen, detener el cambio afectado, documentar la contradicción y pedir una decisión. No resolver una contradicción cambiando silenciosamente pruebas o contratos.

## Flujo TDD obligatorio durante la implementación

Para todo cambio de comportamiento:

1. Convertir el requisito o criterio de aceptación en una prueba pequeña.
2. Ejecutarla y comprobar que falla por la razón esperada.
3. Implementar el mínimo cambio que la haga pasar.
4. Refactorizar manteniendo la suite en verde.
5. Ejecutar las verificaciones específicas del módulo y después las mismas puertas que ejecuta CI.

Excepciones: documentación, formato y cambios puramente declarativos que no alteren comportamiento. Las migraciones, contratos, configuración de mensajería y concurrencia sí requieren pruebas adecuadas.

## Reglas de calidad

- No borrar, desactivar, ignorar ni relajar una prueba para obtener un resultado verde sin aprobación explícita.
- No reducir umbrales de cobertura, análisis estático o seguridad.
- Una prueba debe verificar comportamiento observable; evitar pruebas que solo repitan la implementación.
- Unit tests: dominio y casos de uso sin levantar Spring. Los dobles de prueba se colocan en puertos, no alrededor de entidades de dominio.
- Integration tests: PostgreSQL y RabbitMQ reales mediante Testcontainers; no sustituir PostgreSQL por H2.
- Cambios de concurrencia: ejecutar las pruebas deterministas de carrera y la prueba concurrente correspondiente.
- Cambios que afecten locks, stock, retries o consumo de mensajes: ejecutar además el smoke de recursos limitados. El fuzzing extensivo se ejecuta con el perfil previsto para ello y debe dejar una semilla reproducible al fallar.
- Cambios de eventos: actualizar primero el contrato AsyncAPI, mantener compatibilidad o versionar el evento, y verificar duplicados y desorden.
- Cambios de API: actualizar primero OpenAPI y agregar pruebas de contrato y aceptación.
- Cambios de persistencia: usar una migración Flyway hacia adelante y probarla desde una base vacía.
- Cambios en datos de demostración: usar únicamente APIs públicas con claves idempotentes; no insertar directamente en las bases.
- Nunca acceder a tablas, repositorios o clases internas de otro microservicio.
- No crear una librería de dominio compartida entre servicios.

## Consistencia distribuida que no debe romperse

- Cada servicio es dueño exclusivo de sus datos.
- Toda publicación derivada de un cambio de negocio pasa por transactional outbox.
- Todo consumidor registra el mensaje en inbox dentro de la misma transacción que su efecto de negocio.
- Los consumidores deben ser seguros frente a duplicados, reintentos y mensajes fuera de orden.
- Inventory nunca puede confirmar una reserva que deje stock negativo.
- Inventory reserva todos los ítems o ninguno y reporta la lista completa de faltantes.
- Una actualización manual de stock no puede fijar existencias físicas por debajo de unidades ya reservadas.
- Reponer unidades usa `POST /products/{productId}/restock`, un `movementId` estable y unicidad persistente; no calcular un valor absoluto en el cliente para sumar stock.
- Recontar usa `PUT /products` con `expectedVersion`; una versión obsoleta devuelve `409` y no sobrescribe cambios concurrentes.
- Una cancelación aceptada es terminal en Order y debe converger a stock liberado o nunca reservado en Inventory.
- No introducir transacciones distribuidas 2PC ni bloqueos entre bases de datos.

## Verificación y entrega

- Leer los workflows de `.github/workflows` cuando existan y reproducir localmente sus comandos relevantes. Durante el bootstrap, crear primero comandos de verificación locales que luego usará CI.
- No afirmar que una prueba pasó si no fue ejecutada en el entorno actual.
- Informar comando, resultado y cualquier verificación no ejecutada.
- Una tarea de implementación no está completa con pruebas fallando, deshabilitadas o sin ejecutar, salvo bloqueo externo claramente documentado.
- Si falla fuzzing o una prueba aleatoria, conservar y reportar la semilla y agregarla al corpus de regresión antes de corregir.
- Los commits deben ser pequeños, coherentes y no mezclar refactors ajenos al cambio.

## Skills del repositorio

- `dry-refactoring` (`.agents/skills/dry-refactoring/SKILL.md`) es la guía obligatoria para detectar duplicación con jscpd y refactorizarla. Leer la skill completa antes de usarla, revisar cada clon en contexto y volver a ejecutar la detección después de cada refactor.
- Las abstracciones extraídas deben ser técnicas y tener un nombre claro; no crear una biblioteca de dominio compartida ni ocultar diferencias reales entre Order e Inventory.
- `find-skills` (`.agents/skills/find-skills/SKILL.md`) se usa únicamente cuando una tarea solicite descubrir o instalar una capacidad adicional; no sustituye las herramientas y reglas de verificación del repositorio.
