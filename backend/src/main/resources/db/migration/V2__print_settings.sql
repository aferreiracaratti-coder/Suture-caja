CREATE TABLE print_settings (
 organization_id BIGINT PRIMARY KEY REFERENCES organizations(id),
 destination VARCHAR(80) NOT NULL DEFAULT 'CAJA',
 printer_id VARCHAR(160),
 enabled BOOLEAN NOT NULL DEFAULT FALSE
);
