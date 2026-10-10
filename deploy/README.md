# Despliegue de Suture Caja

Instancia inicial: `suture-prod-01` (`216.238.119.80`). La API corre como
`suturecaja` bajo systemd en `127.0.0.1:8095`; Nginx sirve Angular y envía
`/api/` a la API. PostgreSQL usa la base y el rol exclusivos `suture_caja`.
El sitio temporal con TLS es `https://caja.216.238.119.80.sslip.io/`.

## Archivos del servidor

- `/opt/suture-caja/suture-caja.jar`: backend compilado.
- `/var/www/suture-caja/`: salida de `npm run build` (`frontend/dist/frontend/browser/`).
- `/etc/systemd/system/suture-caja.service`: unidad basada en `suture-caja.service`.
- `/etc/suture-caja/caja.env`: variables privadas, modo `0600`, propiedad root.
- `/etc/nginx/sites-available/suture-caja`: sitio HTTPS administrado por Certbot.
- `/usr/local/sbin/suture-caja-backup.sh` y unidades `suture-caja-backup.*`:
  copia diaria de PostgreSQL, retención de 14 días.

Las credenciales no se guardan en Git. `CAJA_DB_PASSWORD` se encuentra solo
en el archivo privado del servidor. El bootstrap del administrador se retiró
del ambiente después de crear la cuenta. La primera copia se verificó con
`pg_restore --list`; aún conviene practicar una restauración en una base
aislada antes de operar con datos de un comercio.

## Publicar una nueva versión

Desde la raíz del repositorio:

```bash
(cd backend && mvn test && mvn package -DskipTests)
(cd frontend && npm ci && npm test -- --watch=false && npm run build)
scp backend/target/suture-caja-0.1.0.jar root@216.238.119.80:/opt/suture-caja/suture-caja.jar
scp -r frontend/dist/frontend/browser/. root@216.238.119.80:/var/www/suture-caja/
ssh root@216.238.119.80 'systemctl restart suture-caja && systemctl is-active suture-caja'
```

Flyway aplica las migraciones al arrancar. Antes de actualizar una instancia
con datos reales, ejecutar el backup y revisar el cambio de esquema.

## Dominio definitivo

Configurar `caja.suturesistemas.com` con `A` a `216.238.119.80` y, si se
utiliza IPv6, `AAAA` a `2001:19f0:b800:14d1:5400:06ff:fe9a:66e5`.
Ambas direcciones respondieron con HTTPS durante la instalación. El servidor
actualmente redirige el HTTP de ese nombre al sitio temporal. Cuando el DNS
resuelva públicamente, emitir un certificado para el nombre definitivo y
revisar que Nginx sirva la aplicación en HTTPS directamente:

```bash
sudo certbot --nginx -d caja.suturesistemas.com --redirect
sudo nginx -t && sudo systemctl reload nginx
```

## Comprobaciones

```bash
systemctl status suture-caja --no-pager
systemctl status suture-caja-backup.timer --no-pager
curl -fsS http://127.0.0.1:8095/actuator/health
sudo -u postgres psql -d suture_caja -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;'
```

El agente físico de impresión, pagos bancarios y facturación fiscal no están
conectados en esta instalación. Los tickets son internos no fiscales.
