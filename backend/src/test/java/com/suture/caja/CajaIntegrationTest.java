package com.suture.caja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Runs against a dedicated PostgreSQL database. Set CAJA_TEST_DB_URL before mvn test. */
@SpringBootTest(properties = {
    "caja.bootstrap.organization=Integration fixture",
    "caja.bootstrap.username=integration-bootstrap",
    "caja.bootstrap.password=integration-bootstrap-password"
})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CAJA_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CajaIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("CAJA_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("CAJA_TEST_DB_USERNAME", "caja"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAJA_TEST_DB_PASSWORD", ""));
    }

    @Autowired JdbcTemplate db;
    @Autowired CatalogService catalog;
    @Autowired CashService cash;
    @Autowired SaleService sales;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;

    private record Fixture(long org, long admin, long cashier, long product, long shift, String cashierName) {}

    private Fixture fixture(int stock) {
        String unique = UUID.randomUUID().toString();
        long org = db.queryForObject("INSERT INTO organizations(name) VALUES (?) RETURNING id", Long.class, "Test " + unique);
        long admin = user(org, "admin-" + unique, "ADMINISTRADOR");
        String cashierName = "cashier-" + unique;
        long cashier = user(org, cashierName, "CAJERO");
        long register = Store.num(cash.createRegister(org, new Requests.Register("Principal")), "id");
        long product = Store.num(catalog.createProduct(org, admin, new Requests.Product("Producto", null,
            "SKU-" + unique, "B-" + unique, null, bd("100.00"), bd("40.00"), "UNIT", 0,
            BigDecimal.ONE, true, null)), "id");
        if (stock > 0) catalog.stock(org, admin, new Requests.StockMovement(product, "INITIAL", bd(stock), "Inventario inicial"));
        long shift = Store.num(cash.open(org, cashier, new Requests.Shift(register, bd("200.00"), "Inicio")), "id");
        return new Fixture(org, admin, cashier, product, shift, cashierName);
    }

    private long user(long org, String username, String role) {
        return db.queryForObject("INSERT INTO users(organization_id,username,password_hash,role) VALUES (?,?,?,?) RETURNING id",
            Long.class, org, username, encoder.encode("integration-password"), role);
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static BigDecimal bd(int value) { return BigDecimal.valueOf(value); }

    private Requests.Sale sale(Fixture f, UUID key, int quantity, BigDecimal discount, Requests.Payment... payments) {
        return new Requests.Sale(key, f.shift(), List.of(new Requests.Item(f.product(), bd(quantity), BigDecimal.ZERO)),
            discount, List.of(payments));
    }

    @Test void cashSalePersistsNetCashStockAndPrintOutbox() {
        Fixture f = fixture(5);
        Map<String,Object> result = sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 2, BigDecimal.ZERO, new Requests.Payment("CASH", bd("200.00"), bd("250.00"))));
        long id = Store.num(result, "id");

        assertThat(Store.money(result, "total")).isEqualByComparingTo("200.00");
        assertThat(Store.money(result, "changeAmount")).isEqualByComparingTo("50.00");
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("3");
        assertThat(Store.money(cash.summary(f.org(), f.shift()), "expected")).isEqualByComparingTo("400.00");
        assertThat(db.queryForObject("SELECT amount FROM cash_movements WHERE sale_id=?", BigDecimal.class, id))
            .isEqualByComparingTo("200.00");
        assertThat(db.queryForObject("SELECT count(*) FROM print_jobs WHERE sale_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT unit_cost FROM sale_items WHERE sale_id=?", BigDecimal.class, id))
            .isEqualByComparingTo("40.00");
    }

    @Test void mixedAndElectronicPaymentsUseExactTotalAndCashNet() {
        Fixture f = fixture(4);
        Map<String,Object> result = sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 2, BigDecimal.ZERO,
                new Requests.Payment("CASH", bd("50.00"), bd("100.00")),
                new Requests.Payment("DEBIT_CARD", bd("150.00"), null)));
        assertThat(Store.money(result, "changeAmount")).isEqualByComparingTo("50.00");
        assertThat(Store.money(cash.summary(f.org(), f.shift()), "expected")).isEqualByComparingTo("250.00");
        assertThat(((List<?>)result.get("payments"))).hasSize(2);

        sales.confirm(f.org(), f.cashier(), "CAJERO", sale(f, UUID.randomUUID(), 1, BigDecimal.ZERO,
            new Requests.Payment("CREDIT_CARD", bd("100.00"), null)));
        assertThat(Store.money(cash.summary(f.org(), f.shift()), "expected")).isEqualByComparingTo("250.00");
    }

    @Test void retryIsIdempotentAndDoesNotDecrementStockTwice() {
        Fixture f = fixture(3);
        UUID key = UUID.randomUUID();
        Requests.Sale request = sale(f, key, 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null));
        long first = Store.num(sales.confirm(f.org(), f.cashier(), "CAJERO", request), "id");
        long second = Store.num(sales.confirm(f.org(), f.cashier(), "CAJERO", request), "id");
        assertThat(second).isEqualTo(first);
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("2");
        assertThat(db.queryForObject("SELECT count(*) FROM payments WHERE sale_id=?", Integer.class, first)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM stock_movements WHERE sale_id=?", Integer.class, first)).isEqualTo(1);
    }

    @Test void idempotencyKeyWithDifferentPayloadReturnsConflictWithoutAnotherCharge() {
        Fixture f = fixture(3);
        UUID key = UUID.randomUUID();
        long id = Store.num(sales.confirm(f.org(), f.cashier(), "CAJERO", sale(f, key, 1, BigDecimal.ZERO,
            new Requests.Payment("CASH", bd("100.00"), null))), "id");

        assertThatThrownBy(() -> sales.confirm(f.org(), f.cashier(), "CAJERO", sale(f, key, 2, BigDecimal.ZERO,
            new Requests.Payment("CASH", bd("200.00"), null))))
            .isInstanceOfSatisfying(ApiException.Business.class,
                error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        assertThat(db.queryForObject("SELECT count(*) FROM sales WHERE organization_id=?", Integer.class, f.org())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM payments WHERE sale_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("2");
    }

    @Test void generalDiscountAllocatesAcrossLinesAndPreservesCostSnapshot() {
        Fixture f = fixture(3);
        String unique = UUID.randomUUID().toString();
        long secondProduct = Store.num(catalog.createProduct(f.org(), f.admin(), new Requests.Product(
            "Otro producto", null, "SKU-SECOND-" + unique, null, null, bd("30.00"), bd("12.00"),
            "UNIT", 0, BigDecimal.ZERO, true, null)), "id");
        catalog.stock(f.org(), f.admin(), new Requests.StockMovement(secondProduct, "INITIAL", bd(2), "Inventario inicial"));
        Requests.Sale request = new Requests.Sale(UUID.randomUUID(), f.shift(), List.of(
            new Requests.Item(f.product(), BigDecimal.ONE, bd("5.00")),
            new Requests.Item(secondProduct, BigDecimal.ONE, BigDecimal.ZERO)), bd("10.01"),
            List.of(new Requests.Payment("CASH", bd("114.99"), null)));
        Map<String,Object> result = sales.confirm(f.org(), f.admin(), "ADMINISTRADOR", request);
        long id = Store.num(result, "id");

        Map<String,BigDecimal> sums = db.queryForMap("SELECT SUM(total) AS total, SUM(order_discount) AS discount FROM sale_items WHERE organization_id=? AND sale_id=?", f.org(), id)
            .entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> (BigDecimal)entry.getValue()));
        assertThat(sums.get("total")).isEqualByComparingTo(Store.money(result, "total"));
        assertThat(sums.get("discount")).isEqualByComparingTo("10.01");
        assertThat(db.queryForObject("SELECT count(*) FROM sale_items WHERE sale_id=?", Integer.class, id)).isEqualTo(2);

        catalog.updateProduct(f.org(), f.admin(), f.product(), new Requests.Product("Producto", null,
            "SKU-UPDATED-" + unique, null, null, bd("100.00"), bd("99.00"), "UNIT", 0,
            BigDecimal.ONE, true, null));
        assertThat(db.queryForObject("SELECT unit_cost FROM sale_items WHERE sale_id=? AND product_id=?",
            BigDecimal.class, id, f.product())).isEqualByComparingTo("40.00");
        assertThat(db.queryForObject("SELECT unit_cost FROM sale_items WHERE sale_id=? AND product_id=?",
            BigDecimal.class, id, secondProduct)).isEqualByComparingTo("12.00");
    }

    @Test void insufficientStockOrClosedShiftRollsBackEverything() {
        Fixture f = fixture(1);
        assertThatThrownBy(() -> sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 2, BigDecimal.ZERO, new Requests.Payment("CASH", bd("200.00"), null))))
            .isInstanceOf(ApiException.Business.class);
        assertThat(db.queryForObject("SELECT count(*) FROM sales WHERE organization_id=?", Integer.class, f.org())).isZero();
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("1");

        cash.close(f.org(), f.cashier(), "CAJERO", f.shift(), new Requests.Close(bd("200.00"), null));
        assertThatThrownBy(() -> sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null))))
            .isInstanceOf(ApiException.Business.class);
    }

    @Test void discountNeedsSupervisorAndNeverTrustsClientPrice() {
        Fixture f = fixture(3);
        Requests.Sale discounted = sale(f, UUID.randomUUID(), 1, bd("10.00"),
            new Requests.Payment("CASH", bd("90.00"), null));
        assertThatThrownBy(() -> sales.confirm(f.org(), f.cashier(), "CAJERO", discounted))
            .isInstanceOf(ApiException.Business.class);
        Map<String,Object> result = sales.confirm(f.org(), f.admin(), "ADMINISTRADOR", discounted);
        assertThat(Store.money(result, "total")).isEqualByComparingTo("90.00");
        assertThatThrownBy(() -> sales.confirm(f.org(), f.admin(), "ADMINISTRADOR",
            sale(f, UUID.randomUUID(), 1, bd("101.00"), new Requests.Payment("CASH", bd("1.00"), null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void cancellationRestoresStockAndCashOnce() {
        Fixture f = fixture(2);
        long id = Store.num(sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null))), "id");
        assertThat(sales.cancel(f.org(), f.admin(), id, "Error de carga").get("status")).isEqualTo("CANCELLED");
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("2");
        assertThat(Store.money(cash.summary(f.org(), f.shift()), "expected")).isEqualByComparingTo("200.00");
        assertThatThrownBy(() -> sales.cancel(f.org(), f.admin(), id, "Otra vez"))
            .isInstanceOf(ApiException.Business.class);
    }

    @Test void closingDifferenceNeedsReasonAndIsRecorded() {
        Fixture f = fixture(1);
        sales.confirm(f.org(), f.cashier(), "CAJERO", sale(f, UUID.randomUUID(), 1, BigDecimal.ZERO,
            new Requests.Payment("CASH", bd("100.00"), bd("120.00"))));
        assertThatThrownBy(() -> cash.close(f.org(), f.cashier(), "CAJERO", f.shift(), new Requests.Close(bd("295.00"), null)))
            .isInstanceOf(IllegalArgumentException.class);
        Map<String,Object> closed = cash.close(f.org(), f.cashier(), "CAJERO", f.shift(), new Requests.Close(bd("295.00"), "Faltante contado"));
        assertThat(Store.money(closed, "expectedAmount")).isEqualByComparingTo("300.00");
        assertThat(Store.money(closed, "differenceAmount")).isEqualByComparingTo("-5.00");
    }

    @Test void otherTenantCannotReadOrSpendProductAndSale() throws Exception {
        Fixture first = fixture(2);
        Fixture second = fixture(1);
        long saleId = Store.num(sales.confirm(first.org(), first.cashier(), "CAJERO",
            sale(first, UUID.randomUUID(), 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null))), "id");
        assertThatThrownBy(() -> sales.sale(second.org(), saleId)).isInstanceOf(ApiException.Business.class);
        mvc.perform(get("/api/sales/{id}", saleId).with(httpBasic(second.cashierName(), "integration-password")))
            .andExpect(status().isNotFound());
        assertThatThrownBy(() -> catalog.product(second.org(), first.product())).isInstanceOf(ApiException.Business.class);
        assertThatThrownBy(() -> sales.confirm(second.org(), second.cashier(), "CAJERO",
            new Requests.Sale(UUID.randomUUID(), second.shift(),
                List.of(new Requests.Item(first.product(), BigDecimal.ONE, BigDecimal.ZERO)), BigDecimal.ZERO,
                List.of(new Requests.Payment("CASH", bd("100.00"), null)))))
            .isInstanceOf(ApiException.Business.class);
        sales.confirm(second.org(), second.cashier(), "CAJERO",
            sale(second, UUID.randomUUID(), 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null)));
        assertThat(sales.sales(second.org(), null)).hasSize(1);
    }

    @Test void simultaneousSalesCannotOversell() throws Exception {
        Fixture f = fixture(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Boolean>> attempts = List.of(0, 1).stream().map(index ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        sales.confirm(f.org(), f.cashier(), "CAJERO", sale(f, UUID.randomUUID(), 1,
                            BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null)));
                        return true;
                    } catch (ApiException.Business expected) { return false; }
                }, pool)).toList();
            assertThat(attempts.stream().map(CompletableFuture::join).filter(Boolean::booleanValue)).hasSize(1);
        }
        assertThat(Store.money(catalog.product(f.org(), f.product()), "stock")).isEqualByComparingTo("0");
        assertThat(sales.sales(f.org(), null)).hasSize(1);
    }

    @Test void cashierCannotCancelSaleOverHttp() throws Exception {
        Fixture f = fixture(1);
        long id = Store.num(sales.confirm(f.org(), f.cashier(), "CAJERO",
            sale(f, UUID.randomUUID(), 1, BigDecimal.ZERO, new Requests.Payment("CASH", bd("100.00"), null))), "id");
        mvc.perform(post("/api/sales/{id}/cancel", id).with(httpBasic(f.cashierName(), "integration-password"))
                .contentType("application/json").content("{\"reason\":\"test\"}"))
            .andExpect(status().isForbidden());
        assertThat(sales.sale(f.org(), id).get("status")).isEqualTo("PAID");
    }
}
