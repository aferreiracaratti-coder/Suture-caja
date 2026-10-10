package com.suture.caja;

/** Optional external fiscal boundary. No implementation is registered until Suture Fiscal is available. */
public interface FiscalGateway {
    record FiscalDocument(String externalId, String status, String documentType) {}
    FiscalDocument issue(long organizationId, long saleId, String idempotencyKey);
    FiscalDocument status(long organizationId, String externalId);
    FiscalDocument credit(long organizationId, String externalId, String reason, String idempotencyKey);
}
