# Suture Caja — frontend

Angular 21. Instalación reproducible desde `package-lock.json`:

```bash
cd frontend
npm ci
npm start
```

Abrir `http://localhost:4200`. El proxy de desarrollo envía `/api` al backend local en `http://127.0.0.1:8095`; iniciá primero el backend y PostgreSQL según el README principal. Las credenciales se crean en el backend para cada organización. La contraseña de la sesión sólo se conserva en memoria y se borra al salir o recargar.

La navegación incluye punto de venta, productos, caja, inventario, ventas y reportes. Administradores pueden configurar comercio, usuarios e impresión. Supervisores pueden ver reportes, mover stock, anular ventas y gestionar caja. Los permisos definitivos son validados por el backend. Los pagos con tarjeta y transferencia se registran como declaraciones del cajero; el ticket mostrado es un comprobante interno no fiscal. El botón «Ver ticket» abre la impresión del navegador; «Reimprimir» solicita un trabajo al agente si está configurado.

Atajos en la pantalla de venta: `/` enfoca la búsqueda cuando no hay un campo de texto activo; `F2` abre el cobro; `Escape` cierra el cobro. Un lector de código de barras que envía texto y Enter funciona desde el buscador.

```bash
npm run build
npm test -- --watch=false
```
