# Suture Caja

MVP minorista independiente de Marcha POS. Backend Java 21 / Spring Boot 3.5.6 / PostgreSQL 16 y frontend Angular 21. Los importes se calculan en el backend y las ventas, pagos, movimientos de stock y efectivo se confirman en una transacción.

La instalación inicial del servidor y el procedimiento de actualización están en [deploy/README.md](deploy/README.md).

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

La simulación completa usa un contenedor PostgreSQL 16 descartable, dos bases nuevas, un backend local y un agente de impresión deliberadamente inaccesible. No usa el servidor ni la base demo. Requiere Docker, Java 21, Maven, Node/npm, Python 3, curl y OpenSSL; instalar primero las dependencias del frontend con `cd frontend && npm ci` si faltan.

```bash
bash scripts/run-mvp-simulation.sh
```

El script ejecuta 18 pruebas backend (12 con PostgreSQL), 5 pruebas Angular, el build y 36 verificaciones de una jornada comercial por HTTP. Incluye dos turnos, búsquedas, inventario, permisos, pagos mixtos, idempotencia, descuentos, anulación, arqueo, reportes, stock fraccionario/negativo y fallo de impresión sin revertir la venta. La suite de integración solo se habilita con `CAJA_TEST_DB_URL` en una base dedicada: `mvn test` sin esa variable no comprueba persistencia.

La UI se verificó manualmente en navegador local con una venta completa, datos contrastados directamente en PostgreSQL y vistas de escritorio (1366×768) y móvil (390×844). Esta comprobación visual no forma parte del script automatizado. El hardware térmico real, la emisión fiscal y la reversión bancaria siguen pendientes de pruebas externas. El alta de nuevos tenants fuera del bootstrap inicial es una tarea operativa; no hay pantalla de aprovisionamiento de plataforma. Las devoluciones parciales tampoco están implementadas.

## Impresión y fiscal

La venta crea un `print_job` estable en la misma transacción. Para un backend en la misma PC del agente, configurar `CAJA_PRINT_URL=http://127.0.0.1:17890`, `CAJA_PRINT_TOKEN` y habilitar `print-settings` por organización; el worker envía `POST /print` con el contrato real del agente y reintenta con el mismo `jobId`. El comprobante indica **INTERNO NO FISCAL**. La emisión fiscal real, el Print Service cloud y la prueba física de impresión no forman parte de esta entrega. Ver [contrato de impresión](docs/PRINT.md) y [mapa de reutilización](docs/REUSE.md).
