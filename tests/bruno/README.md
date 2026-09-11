# Aceptación HTTP

Desde el root: `java scripts/Verify.java acceptance`. Ejecuta demo-data y esta colección dos veces
con las mismas claves. Los resultados JUnit están en `reports/verification/acceptance/<run>/`.
No requiere Node, npm ni PowerShell: Bruno CLI 4.1.0 se ejecuta en Docker.

En Bruno Desktop, abrir esta carpeta, seleccionar `local` y cambiar `runId` para un escenario nuevo.
Ejecutar la colección completa en orden; repetir conservando `runId` demuestra replay histórico.
Los UUID de movimientos se derivan de `runId`, nunca se regeneran al repetir una operación.
El sandbox `developer` se necesita exclusivamente para SHA-256 de los datos de prueba.

Las 22 solicitudes cubren los nueve endpoints, errores 400/404/409, confirmación, rechazo y
cancelación con compensación. El polling tiene un límite de 120 intentos de 250 ms.
Las pruebas de 413, 503, timeout y concurrencia determinista se ejecutan en `ServiceIT`.

Referencia: [Bruno CLI](https://docs.usebruno.com/bru-cli/runCollection).
