#!/usr/bin/env python3
"""Jornada comercial contra un backend local con PostgreSQL descartable.

Requiere CAJA_SIMULATION_TEST_DB=1 para evitar ejecutarlo por accidente.
"""
import base64
import datetime as dt
import json
import os
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from decimal import Decimal

BASE = os.getenv("CAJA_SIMULATION_URL", "http://127.0.0.1:8096").rstrip("/")
if not BASE.startswith(("http://127.0.0.1:", "http://localhost:")):
    raise SystemExit("La simulación exige un backend en loopback")
if os.getenv("CAJA_SIMULATION_TEST_DB") != "1":
    raise SystemExit("Definir CAJA_SIMULATION_TEST_DB=1 solo para una base descartable")
ADMIN = (os.environ["CAJA_SIMULATION_ADMIN"], os.environ["CAJA_SIMULATION_ADMIN_PASSWORD"])
checks = 0


def check(condition, name):
    global checks
    assert condition, name
    checks += 1
    print(f"OK {checks:02d} {name}")


def money(value):
    return Decimal(str(value))


def request(method, path, user=ADMIN, payload=None, expected=200):
    auth = base64.b64encode(f"{user[0]}:{user[1]}".encode()).decode()
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        BASE + "/api" + path, data=data, method=method,
        headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            code, body = response.status, response.read()
    except urllib.error.HTTPError as exc:
        code, body = exc.code, exc.read()
    if code != expected:
        raise AssertionError(f"{method} {path}: HTTP {code}, esperado {expected}: {body[:500]!r}")
    return json.loads(body) if body else None


def sale(shift_id, product_id, quantity, payments, discount="0.00", line_discount="0.00", key=None):
    return {
        "idempotencyKey": key or str(uuid.uuid4()), "shiftId": shift_id,
        "items": [{"productId": product_id, "quantity": quantity, "discount": line_discount}],
        "discount": discount, "payments": payments,
    }


suffix = uuid.uuid4().hex[:8]
org = request("GET", "/organization")
check(org["currency"] == "UYU", "organización y moneda inicial")
request("PUT", "/organization", payload={
    "name": "Comercio simulado " + suffix, "currency": "UYU", "allowNegativeStock": False,
})
category = request("POST", "/categories", payload={"name": "Almacén " + suffix})
product_data = {
    "name": "Galletas " + suffix, "description": "Producto de prueba", "sku": "GAL-" + suffix,
    "barcode": "779" + suffix, "categoryId": category["id"], "price": "100.00",
    "cost": "40.00", "unit": "UNIT", "quantityScale": 0,
    "minimumStock": "3", "active": True,
}
product = request("POST", "/products", payload=product_data)
request("POST", "/products", payload=product_data, expected=409)
for term in (product_data["name"], product_data["sku"], product_data["barcode"]):
    found = request("GET", "/products?q=" + urllib.parse.quote(term))
    check(any(row["id"] == product["id"] for row in found), "búsqueda por " + term)
filtered = request("GET", f"/products?categoryId={category['id']}")
check(any(row["id"] == product["id"] for row in filtered), "filtro por categoría")
request("POST", "/inventory/movements", payload={
    "productId": product["id"], "type": "INITIAL", "quantity": 10, "reason": "Inventario inicial",
})
request("POST", "/inventory/movements", payload={
    "productId": product["id"], "type": "IN", "quantity": 2, "reason": "Reposición",
})
request("POST", "/inventory/movements", payload={
    "productId": product["id"], "type": "OUT", "quantity": 1, "reason": "Merma",
})
stock = request("GET", f"/inventory/movements?productId={product['id']}")
check(len(stock) == 3 and money(stock[0]["balanceAfter"]) == 11, "inventario trazable")
request("POST", "/inventory/movements", payload={
    "productId": product["id"], "type": "ADJUST", "quantity": 1,
    "reason": "Corrección de conteo",
})
check(money(request("GET", f"/inventory/movements?productId={product['id']}")[0]["balanceAfter"]) == 12,
      "ajuste de inventario con motivo")
request("POST", "/inventory/movements", payload={
    "productId": product["id"], "type": "OUT", "quantity": 20, "reason": "Exceso",
}, expected=409)

cashier = ("sim-cajero-" + suffix, secrets.token_urlsafe(18))
supervisor = ("sim-super-" + suffix, secrets.token_urlsafe(18))
for credentials, role in ((cashier, "CAJERO"), (supervisor, "SUPERVISOR")):
    request("POST", "/users", payload={
        "username": credentials[0], "password": credentials[1], "role": role,
    })
check(request("GET", "/me", cashier)["role"] == "CAJERO", "inicio de sesión de cajero")
request("GET", "/me", (cashier[0], "wrong-password"), expected=401)
request("POST", "/products", cashier, product_data, expected=403)
request("POST", "/inventory/movements", cashier, {
    "productId": product["id"], "type": "IN", "quantity": 1, "reason": "Sin permiso",
}, expected=403)
request("GET", "/reports/dashboard", cashier, expected=403)
check(True, "credenciales y permisos rechazados en backend")

register = request("POST", "/registers", payload={"name": "Simulación " + suffix})
shift = request("POST", "/shifts", cashier, {
    "registerId": register["id"], "openingAmount": "200.00", "notes": "Jornada de simulación",
})
request("POST", "/shifts", cashier, {
    "registerId": register["id"], "openingAmount": "0.00",
}, expected=409)
check(shift["status"] == "OPEN", "apertura única por caja")

cash = sale(shift["id"], product["id"], 2,
            [{"method": "CASH", "amount": "200.00", "tendered": "250.00"}])
paid = request("POST", "/sales", cashier, cash)
check(money(paid["total"]) == 200 and money(paid["changeAmount"]) == 50,
      "venta en efectivo y vuelto")
retry = request("POST", "/sales", cashier, cash)
check(retry["id"] == paid["id"], "reintento idempotente sin venta adicional")
changed = dict(cash, discount="1.00")
request("POST", "/sales", cashier, changed, expected=409)
check(len(request("GET", f"/sales?shiftId={shift['id']}", cashier)) == 1,
      "clave reutilizada con otro contenido rechazada")
ticket = request("GET", f"/sales/{paid['id']}/ticket", cashier)
check(ticket["kind"] == "INTERNAL_NON_FISCAL" and len(ticket["sale"]["items"]) == 1,
      "ticket interno no fiscal")
jobs = request("GET", f"/print-jobs?saleId={paid['id']}")
check(len(jobs) == 1 and jobs[0]["status"] == "PENDING", "trabajo de impresión persistido")
request("POST", f"/sales/{paid['id']}/reprint", cashier, expected=403)
request("POST", f"/sales/{paid['id']}/reprint", supervisor)
check(len(request("GET", f"/print-jobs?saleId={paid['id']}")) == 2,
      "reimpresión autorizada y auditable")

card = request("POST", "/sales", cashier, sale(shift["id"], product["id"], 1,
               [{"method": "DEBIT_CARD", "amount": "100.00"}]))
check(card["payments"][0]["externalStatus"] == "DECLARED", "débito declarado")
mixed = request("POST", "/sales", cashier, sale(shift["id"], product["id"], 1, [
    {"method": "CASH", "amount": "25.00", "tendered": "30.00"},
    {"method": "CREDIT_CARD", "amount": "25.00"},
    {"method": "TRANSFER", "amount": "25.00"},
    {"method": "OTHER", "amount": "25.00"},
]))
check(money(mixed["changeAmount"]) == 5 and len(mixed["payments"]) == 4,
      "pago mixto, crédito, transferencia, otro y vuelto")
discount_request = sale(shift["id"], product["id"], 1,
                        [{"method": "DEBIT_CARD", "amount": "85.00"}], "5.00", "10.00")
request("POST", "/sales", cashier, discount_request, expected=403)
discounted = request("POST", "/sales", supervisor, discount_request)
check(money(discounted["total"]) == 85 and money(discounted["items"][0]["unitCost"]) == 40,
      "descuento autorizado y costo histórico")
request("POST", "/sales", cashier, sale(shift["id"], product["id"], 1,
        [{"method": "CASH", "amount": "99.00"}]), expected=400)
request("POST", "/sales", cashier, sale(shift["id"], product["id"], 100,
        [{"method": "CASH", "amount": "10000.00"}]), expected=409)
check(True, "pago incompleto y stock insuficiente rechazados")

request("POST", f"/shifts/{shift['id']}/movements", supervisor, {
    "type": "IN", "amount": "20.00", "reason": "Cambio de caja",
})
request("POST", f"/shifts/{shift['id']}/movements", supervisor, {
    "type": "OUT", "amount": "10.00", "reason": "Retiro autorizado",
})
summary = request("GET", f"/shifts/{shift['id']}/summary", cashier)
check(money(summary["expected"]) == 435, "arqueo usa efectivo neto del vuelto")
request("POST", f"/sales/{mixed['id']}/cancel", cashier, {"reason": "Cliente desistió"}, expected=403)
cancelled = request("POST", f"/sales/{mixed['id']}/cancel", supervisor,
                    {"reason": "Cliente desistió"})
check(cancelled["status"] == "CANCELLED", "anulación autorizada sin borrar venta")
request("POST", f"/sales/{mixed['id']}/cancel", supervisor,
        {"reason": "Duplicada"}, expected=409)
summary = request("GET", f"/shifts/{shift['id']}/summary", cashier)
check(money(summary["expected"]) == 410, "anulación revierte efectivo en caja")
check(money(next(p for p in request("GET", "/products", cashier)
                 if p["id"] == product["id"])["stock"]) == 8,
      "anulación repone stock")
movements = request("GET", f"/shifts/{shift['id']}/movements", cashier)
check(any(m["type"] == "CANCEL_CASH" for m in movements), "devolución de efectivo trazable")

today = dt.datetime.now(dt.timezone.utc).date()
report_path = f"/reports?from={today-dt.timedelta(days=1)}&to={today+dt.timedelta(days=1)}"
report = request("GET", report_path)
check(money(report["daily"][0]["total"]) == 385 and report["cancellations"][0]["count"] == 1,
      "reportes excluyen anuladas y cuentan anulaciones")
check(money(report["discounts"][0]["total"]) == 15,
      "reporte de descuentos reales")
filtered_report = request("GET", report_path + f"&registerId={register['id']}&cashierId={request('GET', '/me', cashier)['id']}&categoryId={category['id']}")
check(money(filtered_report["daily"][0]["total"]) == 300 and
      filtered_report["products"][0]["productId"] == product["id"],
      "reportes filtrados por caja, cajero y categoría")
dashboard = request("GET", "/reports/dashboard")
check(money(dashboard["today"]["salesTotal"]) == 385,
      "dashboard consulta ventas persistidas")
request("POST", f"/shifts/{shift['id']}/close", cashier,
        {"countedAmount": "400.00"}, expected=400)
closed = request("POST", f"/shifts/{shift['id']}/close", cashier,
                 {"countedAmount": "400.00", "reason": "Faltante encontrado en arqueo"})
check(closed["status"] == "CLOSED" and money(closed["differenceAmount"]) == -10,
      "cierre con diferencia motivada")
request("POST", f"/shifts/{shift['id']}/close", cashier,
        {"countedAmount": "400.00", "reason": "Segundo cierre"}, expected=409)
request("POST", "/sales", cashier, sale(shift["id"], product["id"], 1,
        [{"method": "CASH", "amount": "100.00"}]), expected=409)
check(len(request("GET", "/audit")) >= 8, "auditoría de operaciones")
request("PUT", "/print-settings", cashier, {
    "destination": "CAJA", "printerId": None, "enabled": True,
}, expected=403)
settings = request("PUT", "/print-settings", payload={
    "destination": "CAJA_SIMULADA", "printerId": None, "enabled": True,
})
check(settings["enabled"] and settings["destination"] == "CAJA_SIMULADA",
      "configuración de impresión por organización")

# Segundo turno: cantidades fraccionarias y política explícita de stock negativo.
fractional = request("POST", "/products", payload={
    "name": "Harina por peso " + suffix, "sku": "HAR-" + suffix,
    "categoryId": category["id"], "price": "10.00", "cost": "4.00",
    "unit": "KG", "quantityScale": 3, "minimumStock": "0", "active": True,
})
request("POST", "/inventory/movements", payload={
    "productId": fractional["id"], "type": "INITIAL", "quantity": "2.500",
    "reason": "Carga por peso",
})
shift2 = request("POST", "/shifts", cashier, {
    "registerId": register["id"], "openingAmount": "0.00",
})
fractional_sale = request("POST", "/sales", cashier, sale(shift2["id"], fractional["id"],
    "0.375", [{"method": "CASH", "amount": "3.75", "tendered": "5.00"}]))
check(money(fractional_sale["total"]) == Decimal("3.75"),
      "venta fraccionaria y redondeo monetario")
request("POST", "/sales", cashier, sale(shift2["id"], fractional["id"],
    "3.000", [{"method": "CASH", "amount": "30.00"}]), expected=409)
request("PUT", "/organization", payload={
    "name": "Comercio simulado " + suffix, "currency": "UYU", "allowNegativeStock": True,
})
negative_sale = request("POST", "/sales", cashier, sale(shift2["id"], fractional["id"],
    "3.000", [{"method": "CASH", "amount": "30.00"}]))
remaining = next(p for p in request("GET", "/products") if p["id"] == fractional["id"])
check(negative_sale["status"] == "PAID" and money(remaining["stock"]) == Decimal("-0.875"),
      "política de stock negativo configurable")
request("PUT", "/organization", payload={
    "name": "Comercio simulado " + suffix, "currency": "UYU", "allowNegativeStock": False,
})
request("POST", f"/shifts/{shift2['id']}/close", cashier,
        {"countedAmount": "33.75"})
check(money(request("GET", f"/shifts/{shift2['id']}/summary")["expected"]) == Decimal("33.75"),
      "segundo turno cerrado y persistido")
if os.getenv("CAJA_SIMULATION_EXPECT_PRINT_FAILURE") == "1":
    # El backend debe apuntar a un agente local deliberadamente inaccesible.
    for _ in range(20):
        failed_jobs = request("GET", f"/print-jobs?saleId={paid['id']}")
        if any(job["attempts"] > 0 and job["status"] == "PENDING" for job in failed_jobs):
            break
        time.sleep(1)
    check(any(job["attempts"] > 0 and job["status"] == "PENDING" for job in failed_jobs)
          and request("GET", f"/sales/{paid['id']}")["status"] == "PAID",
          "fallo de impresión no revierte ni duplica la venta")
print(f"SIMULACIÓN COMPLETA: {checks} verificaciones, venta {paid['id']}, turno {shift['id']}")
