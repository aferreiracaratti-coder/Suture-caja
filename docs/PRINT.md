# Contrato de tickets de Suture Caja

Fuentes verificadas: [guía de integración](/home/angel/Proyectos/Suture-print-agent/docs/software-integration.md), [modelo del agente](/home/angel/Proyectos/Suture-print-agent/internal/model/model.go), [API local](/home/angel/Proyectos/Suture-print-agent/internal/api/local.go) y [cola](/home/angel/Proyectos/Suture-print-agent/internal/queue/service.go). El índice del agente (generación `2026-09-12T13:31:09Z`) no siguió estos archivos Go; se leyeron directamente.

## Backend local en la misma PC que el agente

El agente ofrece `POST http://127.0.0.1:17890/print`, `GET /jobs` y `GET /printers`. Requiere `Authorization: Bearer <token local>` salvo `/health`. El token debe residir en el backend, nunca en Angular. El request incluye `jobId` estable, `destination` configurado o `printerId`, `copies` y `document.elements`. Ejemplo de ticket **interno no fiscal**:

```json
{
  "jobId": "suture-caja:tenant-123:sale-456:ticket:v1",
  "destination": "CAJA_TICKET",
  "copies": 1,
  "document": {"elements": [
    {"type": "text", "value": "SUTURE CAJA", "align": "CENTER", "bold": true},
    {"type": "text", "value": "COMPROBANTE INTERNO NO FISCAL"},
    {"type": "separator"},
    {"type": "text", "value": "Venta 456"},
    {"type": "text", "value": "2 x Producto       250,00"},
    {"type": "text", "value": "TOTAL             250,00", "bold": true},
    {"type": "cut"}
  ]}
}
```

El alias es un ejemplo y requiere configuración por comercio/agente; no reutilizar `KITCHEN` o `RECEPTION` de otros productos. El ancho de papel se configura en la impresora (`paperWidth`); el ticket debe probarse con el dispositivo de 58 u 80 mm elegido.

`202` significa persistido localmente, `200` con `duplicate:true` significa `jobId` ya conocido. Un timeout se reintenta con el mismo ID y documento. `PRINTED` indica escritura aceptada por socket o cola del sistema, no salida física comprobada. `NEEDS_REVIEW` requiere inspección antes de emitir una reimpresión intencional con un nuevo ID autorizado. El agente limita el cuerpo a 1 MiB, 2.000 elementos y 10 copias según su guía/API. La venta debe confirmarse en su transacción antes de intentar imprimir; una falla de entrega no puede crear otra venta ni otro cobro.

## Backend SaaS o en otra PC

La API loopback del agente no es accesible de forma segura desde un backend remoto. El contrato documentado para cloud es `POST /api/v1/print-jobs` por HTTPS a Suture Print Service, con autenticación servidor a servidor y asignación de tenant/destino en ese servicio; el agente mantiene WSS saliente. La guía del repositorio del agente declara ese servicio fuera de su paquete. Su disponibilidad real, provisión para Caja y prueba física requieren verificación independiente antes de activar impresión cloud. No enviar tokens al navegador ni exponer el puerto local.
