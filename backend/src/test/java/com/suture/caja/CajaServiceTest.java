package com.suture.caja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CajaServiceTest {
    private static final long ORG = 7L, ACTOR = 8L, SHIFT = 9L, PRODUCT = 10L;

    private static final class ReadOnlyStore extends Store {
        ReadOnlyStore() { super(null); }
        @Override public Map<String,Object> one(String sql, Object... args) {
            if (sql.contains("organizations") && sql.contains("FOR UPDATE")) return Map.of("id", ORG);
            if (sql.contains("FROM products")) return Map.of("id", PRODUCT, "name", "Producto", "sku", "SKU",
                "price", new BigDecimal("100.00"), "cost", new BigDecimal("40.00"),
                "quantityScale", 0, "active", true, "stock", new BigDecimal("10.000"));
            if (sql.contains("FROM organizations")) return Map.of("name", "Comercio", "currency", "UYU",
                "allowNegativeStock", false);
            throw new AssertionError("Unexpected query: " + sql);
        }
        @Override public List<Map<String,Object>> many(String sql, Object... args) { return List.of(); }
        @Override public Long id(String sql, Object... args) { throw new AssertionError("Validation must precede writes"); }
        @Override public void run(String sql, Object... args) { throw new AssertionError("Validation must precede writes"); }
    }

    private SaleService sales(ReadOnlyStore store, CashService cash) {
        when(cash.openShift(ORG, SHIFT)).thenReturn(Map.of("status", "OPEN", "openedBy", ACTOR));
        return new SaleService(store, cash, mock(CatalogService.class));
    }

    private Requests.Sale request(BigDecimal quantity, BigDecimal discount, Requests.Payment... payments) {
        return new Requests.Sale(UUID.randomUUID(), SHIFT,
            List.of(new Requests.Item(PRODUCT, quantity, BigDecimal.ZERO)), discount, List.of(payments));
    }

    @Test void rejectsPaymentThatDoesNotMatchServerCalculatedPriceBeforeWriting() {
        SaleService sales = sales(new ReadOnlyStore(), mock(CashService.class));
        assertThatThrownBy(() -> sales.confirm(ORG, ACTOR, "CAJERO",
            request(new BigDecimal("2"), BigDecimal.ZERO,
                new Requests.Payment("CASH", new BigDecimal("199.00"), new BigDecimal("200.00")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200.00");
    }

    @Test void rejectsCashTenderBelowDeclaredNetAmountBeforeWriting() {
        SaleService sales = sales(new ReadOnlyStore(), mock(CashService.class));
        assertThatThrownBy(() -> sales.confirm(ORG, ACTOR, "CAJERO",
            request(BigDecimal.ONE, BigDecimal.ZERO,
                new Requests.Payment("CASH", new BigDecimal("100.00"), new BigDecimal("99.00")))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Efectivo entregado insuficiente");
    }

    @Test void requiresSupervisorForDiscountBeforeWriting() {
        SaleService sales = sales(new ReadOnlyStore(), mock(CashService.class));
        assertThatThrownBy(() -> sales.confirm(ORG, ACTOR, "CAJERO",
            request(BigDecimal.ONE, new BigDecimal("10.00"),
                new Requests.Payment("CASH", new BigDecimal("90.00"), null))))
            .isInstanceOf(ApiException.Business.class).hasMessageContaining("Descuento requiere supervisor");
    }

    @Test void internalTicketDocumentCarriesNonFiscalLegendAndPaymentBreakdown() {
        SaleService sales = mock(SaleService.class);
        when(sales.sale(ORG, 23L)).thenReturn(Map.of(
            "id", 23L, "createdAt", "2026-10-09T10:00:00Z", "total", new BigDecimal("150.00"),
            "changeAmount", new BigDecimal("50.00"),
            "items", List.of(Map.of("quantity", BigDecimal.ONE, "productName", "Producto", "total", new BigDecimal("150.00"))),
            "payments", List.of(Map.of("method", "CASH", "amount", new BigDecimal("50.00")),
                Map.of("method", "DEBIT_CARD", "amount", new BigDecimal("100.00")))));
        PrintService print = new PrintService(new ReadOnlyStore(), sales, mock(CatalogService.class), new ObjectMapper());
        Map<String,Object> ticket = print.ticket(ORG, 23L);
        assertThat(ticket.get("kind")).isEqualTo("INTERNAL_NON_FISCAL");
        assertThat(ticket.get("legend")).isEqualTo("COMPROBANTE INTERNO NO FISCAL");
        @SuppressWarnings("unchecked") Map<String,Object> document = ReflectionTestUtils.invokeMethod(print,
            "document", Map.of("organizationId", ORG, "saleId", 23L, "jobId", "job-23", "destination", "CAJA"));
        assertThat(document).containsEntry("jobId", "job-23").containsEntry("destination", "CAJA");
        @SuppressWarnings("unchecked") List<Map<String,Object>> elements =
            (List<Map<String,Object>>)((Map<String,Object>)document.get("document")).get("elements");
        assertThat(elements.stream().filter(e -> "text".equals(e.get("type"))).map(e -> e.get("value").toString()))
            .anyMatch(value -> value.contains("NO FISCAL"))
            .anyMatch(value -> value.contains("DEBIT_CARD 100.00"))
            .anyMatch(value -> value.contains("Vuelto 50.00"));
    }
}
