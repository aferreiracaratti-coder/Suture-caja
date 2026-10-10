package com.suture.caja;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class Requests {
    private Requests() {}
    public record Category(@NotBlank String name) {}
    public record Product(@NotBlank String name, String description, @NotBlank String sku, String barcode, Long categoryId,
        @NotNull @DecimalMin("0") BigDecimal price, @NotNull @DecimalMin("0") BigDecimal cost,
        @NotBlank String unit, @Min(0) @Max(3) int quantityScale, @NotNull @DecimalMin("0") BigDecimal minimumStock,
        boolean active, String imageUrl) {}
    public record Register(@NotBlank String name) {}
    public record Shift(@NotNull Long registerId, @NotNull @DecimalMin("0") BigDecimal openingAmount, String notes) {}
    public record Close(@NotNull @DecimalMin("0") BigDecimal countedAmount, String reason) {}
    public record CashMovement(@NotBlank String type, @NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String reason) {}
    public record StockMovement(@NotNull Long productId, @NotBlank String type, @NotNull BigDecimal quantity, @NotBlank String reason) {}
    public record Item(@NotNull Long productId, @NotNull @DecimalMin("0.001") BigDecimal quantity, @NotNull @DecimalMin("0") BigDecimal discount) {}
    public record Payment(@NotBlank String method, @NotNull @DecimalMin("0.01") BigDecimal amount, BigDecimal tendered) {}
    public record Sale(@NotNull UUID idempotencyKey, @NotNull Long shiftId, @NotEmpty List<@Valid Item> items,
        @NotNull @DecimalMin("0") BigDecimal discount, @NotEmpty List<@Valid Payment> payments) {}
    public record Cancel(@NotBlank String reason) {}
    public record User(@NotBlank String username, @NotBlank String password, @NotBlank String role) {}
    public record Organization(@NotBlank String name, @NotBlank String currency, boolean allowNegativeStock) {}
    public record PrintSettings(@NotBlank String destination, String printerId, boolean enabled) {}
}
