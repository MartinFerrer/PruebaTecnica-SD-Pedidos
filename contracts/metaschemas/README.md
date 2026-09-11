# Metaschemas oficiales versionados

La validación es local y no descarga esquemas durante pruebas ni consumo AMQP.

- `openapi-3.1.json`: [OpenAPI 3.1, schema 2025-11-23](https://spec.openapis.org/oas/3.1/schema/2025-11-23).
- `asyncapi-3.0.0.json`: [AsyncAPI 3.0.0, bundled without $id](https://github.com/asyncapi/spec-json-schemas/blob/master/schemas/3.0.0-without-%24id.json).

Los documentos completos se validan en `ContractTest`. Los schemas JSON embebidos se comprueban
además con el metaschema 2020-12 incluido en
[networknt JSON Schema Validator](https://github.com/networknt/json-schema-validator).
Los ocho eventos se validan con assertions de formatos en `EventContractTest` y al consumir.
Una actualización de estos archivos debe revisarse junto con sus pruebas negativas; no regenerarlos
desde una URL no versionada dentro de CI.
