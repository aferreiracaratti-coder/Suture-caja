# Suture Caja

MVP minorista independiente de Marcha POS. Backend Java 21 / Spring Boot 3.5.6 / PostgreSQL 16 y frontend Angular 21. Los importes se calculan en el backend y las ventas, pagos, movimientos de stock y efectivo se confirman en una transacción.

## Entorno local

Se necesitan Java 21, Maven, Node 24, npm y PostgreSQL 16 (el `compose.yaml` ofrece una instancia aislada). Definir una contraseña propia para la base y otra, de al menos 12 caracteres, para el administrador. No se incluyen credenciales reales.

```bash
export CAJA_DB_PASSWORD='elegir-una-clave-local'
docker compose up -d postgres
export CAJA_DB_URL='jdbc:postgresql://127.0.0.1:55435/suture_caja_demo'
export CAJA_DB_USERNAME='caja'
export CAJA_BOOTSTRAP_ORGANIZATION='Demo Suture Caja'
export CAJA_BOOTSTRAP_USERNAME='administrador'
export CAJA_BOOTSTRAP_PASSWORD='elegir-otra-clave-de-12-o-mas'
cd backend && mvn spring-boot:run
```

Flyway ejecuta `V1` a `V4` al iniciar. En otra terminal, desde la raíz:

```bash
cd frontend && npm ci && npm start
```

Abrir `http://localhost:4200` e ingresar con el usuario y contraseña definidos en las variables de bootstrap. La API escucha en `http://127.0.0.1:8095`; el proxy Angular dirige `/api` allí. La contraseña del navegador queda solo en memoria de la pestaña.

Para cargar cinco productos en la **base demo únicamente**, una vez iniciado el backend:

```bash
PGPASSWORD="$CAJA_DB_PASSWORD" psql -h 127.0.0.1 -p 55435 -U caja -d suture_caja_demo -v ON_ERROR_STOP=1 -f scripts/demo.sql
```

## Flujo operativo

El bootstrap crea la organización, un administrador y `Caja principal`. Desde la UI, abrir un turno con fondo inicial; crear categorías/productos (administrador); cargar inventario mediante `POST /api/inventory/movements`; buscar y cobrar; cerrar con arqueo; consultar ventas y reportes. Un administrador puede crear cajeros/supervisores mediante `POST /api/users`.

La venta acepta `idempotencyKey` UUID, `shiftId`, líneas `{productId,quantity,discount}`, descuento general y pagos `{method,amount,tendered}`. `amount` es el importe aplicado al total; `tendered` solo para efectivo. El backend calcula precios vigentes, vuelto, stock y efectivo neto. Las tarjetas y transferencias quedan como cobros **declarados**, sin confirmación del adquirente. Un descuento requiere supervisor o administrador. La anulación comercial revierte stock y, si hubo efectivo, registra salida en un turno abierto de la misma caja. Los pagos electrónicos precisan reversión fuera del sistema; un documento fiscal vinculado bloquea la anulación comercial hasta resolverlo.

## API principal

| Recurso | Rutas |
|---|---|
| Identidad y comercio | `GET /api/me`, `GET/PUT /api/organization`, `GET/POST /api/users` |
| Catálogo e inventario | `GET/POST /api/categories`, `GET/POST /api/products`, `PUT /api/products/{id}`, `GET/POST /api/inventory/movements` |
| Cajas | `GET/POST /api/registers`, `GET/POST /api/shifts`, `GET /api/shifts/{id}/summary`, `POST /api/shifts/{id}/movements`, `POST /api/shifts/{id}/close` |
| Ventas | `GET/POST /api/sales`, `GET /api/sales/{id}`, `POST /api/sales/{id}/cancel`, `GET /api/sales/{id}/ticket` |
| Reportes | `GET /api/reports/dashboard`, `GET /api/reports?from=YYYY-MM-DD&to=YYYY-MM-DD`, `GET /api/audit` |
| Impresión | `GET/PUT /api/print-settings`, `POST /api/sales/{id}/reprint` |

Todas las rutas `/api` exigen HTTP Basic. El tenant se toma del usuario autenticado, no de datos enviados por Angular. `ADMINISTRADOR` gestiona usuarios, catálogo, cajas, comercio e impresión; `SUPERVISOR` autoriza descuentos/anulaciones/ajustes y consulta reportes; `CAJERO` opera su turno y vende sin descuentos. Respuestas de error de negocio: `{ "error": "mensaje" }` con estado 400/403/404/409. Para piloto remoto debe usarse HTTPS y una política de sesión más robusta; HTTP Basic aquí está orientado a operación local controlada.

## Pruebas

```bash
cd backend && mvn test
cd frontend && npm run build
```

La suite de integración PostgreSQL se habilita con `CAJA_TEST_DB_URL` apuntando a una **base de pruebas dedicada** (nunca la base demo ni producción), junto con `CAJA_TEST_DB_USERNAME` y `CAJA_TEST_DB_PASSWORD`. Ejecuta migraciones y ventas/arqueos reales sobre esa base. `mvn test` sin esa variable no valida persistencia PostgreSQL. En este workspace el sandbox impidió abrir sockets para PostgreSQL; el flujo completo con navegador y hardware térmico queda pendiente de ejecutar en un entorno de piloto.

En una base de prueba dedicada, `CAJA_SMOKE_TEST_DB=1 CAJA_SMOKE_ADMIN=... CAJA_SMOKE_ADMIN_PASSWORD=... python scripts/api-smoke.py` recorre alta de producto, cajero, apertura, venta, stock, ticket, cierre y reporte. Este script quedó preparado, pero no se pudo ejecutar aquí por la misma restricción de PostgreSQL.

Antes del primer comercio real hay que ejecutar las 12 pruebas PostgreSQL en una base aislada, completar una jornada E2E en navegador, probar la impresora física elegida y configurar HTTPS, backups, monitoreo y credenciales propias. El alta de nuevos tenants fuera del bootstrap inicial es todavía una tarea operativa; no hay pantalla de aprovisionamiento de plataforma. Las devoluciones parciales, reversión bancaria y emisión/nota de crédito fiscal no están implementadas.

## Impresión y fiscal

La venta crea un `print_job` estable en la misma transacción. Para un backend en la misma PC del agente, configurar `CAJA_PRINT_URL=http://127.0.0.1:17890`, `CAJA_PRINT_TOKEN` y habilitar `print-settings` por organización; el worker envía `POST /print` con el contrato real del agente y reintenta con el mismo `jobId`. El comprobante indica **INTERNO NO FISCAL**. La emisión fiscal real, el Print Service cloud y la prueba física de impresión no forman parte de esta entrega. Ver [contrato de impresión](docs/PRINT.md) y [mapa de reutilización](docs/REUSE.md).
