#!/usr/bin/env python3
"""Full-day API smoke test against a dedicated local Caja test database."""
import base64
import datetime as dt
import json
import os
import secrets
import urllib.error
import urllib.request
import uuid
from decimal import Decimal

base = os.environ.get("CAJA_SMOKE_URL", "http://127.0.0.1:8095").rstrip("/")
if not base.startswith(("http://127.0.0.1:", "http://localhost:")):
    raise SystemExit("CAJA_SMOKE_URL debe apuntar al backend de prueba local")
if os.environ.get("CAJA_SMOKE_TEST_DB") != "1":
    raise SystemExit("Definir CAJA_SMOKE_TEST_DB=1 solo para una base de prueba dedicada")
admin = os.environ["CAJA_SMOKE_ADMIN"]
admin_password = os.environ["CAJA_SMOKE_ADMIN_PASSWORD"]


def api(method, path, user, password, data=None):
    auth = base64.b64encode(f"{user}:{password}".encode()).decode()
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request(
        base + "/api" + path, data=body, method=method,
        headers={"Authorization": "Basic " + auth, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"{method} {path}: HTTP {exc.code} {exc.read().decode()}") from exc


suffix = uuid.uuid4().hex[:10]
shop = api("GET", "/organization", admin, admin_password)
assert shop["name"]
category = api("POST", "/categories", admin, admin_password, {"name": "Smoke " + suffix})
product = api("POST", "/products", admin, admin_password, {
    "name": "Producto smoke " + suffix, "sku": "SMOKE-" + suffix,
    "categoryId": category["id"], "price": "100.00", "cost": "40.00",
    "unit": "UNIT", "quantityScale": 0, "minimumStock": "1.000", "active": True,
})
api("POST", "/inventory/movements", admin, admin_password, {
    "productId": product["id"], "type": "INITIAL", "quantity": 5, "reason": "Preparación smoke",
})
cashier = "smoke-" + suffix
cashier_password = secrets.token_urlsafe(24)
api("POST", "/users", admin, admin_password, {
    "username": cashier, "password": cashier_password, "role": "CAJERO",
})
register = api("GET", "/registers", cashier, cashier_password)[0]
shift = api("POST", "/shifts", cashier, cashier_password, {
    "registerId": register["id"], "openingAmount": "200.00", "notes": "Smoke",
})
sale = api("POST", "/sales", cashier, cashier_password, {
    "idempotencyKey": str(uuid.uuid4()), "shiftId": shift["id"],
    "items": [{"productId": product["id"], "quantity": 2, "discount": "0.00"}],
    "discount": "0.00", "payments": [{"method": "CASH", "amount": "200.00", "tendered": "250.00"}],
})
assert Decimal(str(sale["total"])) == Decimal("200.00")
assert Decimal(str(sale["changeAmount"])) == Decimal("50.00")
remaining = next(p for p in api("GET", "/products", cashier, cashier_password) if p["id"] == product["id"])
assert Decimal(str(remaining["stock"])) == 3
ticket = api("GET", f"/sales/{sale['id']}/ticket", cashier, cashier_password)
assert ticket["kind"] == "INTERNAL_NON_FISCAL"
summary = api("GET", f"/shifts/{shift['id']}/summary", cashier, cashier_password)
assert Decimal(str(summary["expected"])) == Decimal("400.00")
closed = api("POST", f"/shifts/{shift['id']}/close", cashier, cashier_password, {
    "countedAmount": "400.00", "reason": None,
})
assert Decimal(str(closed["differenceAmount"])) == 0
today = dt.datetime.now(dt.timezone.utc).date()
report = api("GET", f"/reports?from={today-dt.timedelta(days=1)}&to={today+dt.timedelta(days=1)}", admin, admin_password)
assert any(Decimal(str(row["total"])) >= 200 for row in report["daily"])
print(f"OK org={shop['id']} sale={sale['id']} shift={shift['id']} ticket={ticket['kind']}")
