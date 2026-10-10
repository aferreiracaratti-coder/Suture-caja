-- SOLO desarrollo. Requiere bootstrap con CAJA_BOOTSTRAP_ORGANIZATION='Demo Suture Caja'.
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'suture_caja_demo' THEN RAISE EXCEPTION 'La semilla demo solo admite suture_caja_demo'; END IF;
 IF NOT EXISTS (SELECT 1 FROM organizations WHERE name='Demo Suture Caja') THEN RAISE EXCEPTION 'Falta organización demo'; END IF;
END $$;
INSERT INTO categories(organization_id,name)
SELECT id,'Almacén' FROM organizations WHERE name='Demo Suture Caja'
ON CONFLICT (organization_id,name) DO NOTHING;
INSERT INTO categories(organization_id,name)
SELECT id,'Bebidas' FROM organizations WHERE name='Demo Suture Caja'
ON CONFLICT (organization_id,name) DO NOTHING;
INSERT INTO products(organization_id,category_id,name,sku,barcode,price,cost,unit,quantity_scale,minimum_stock)
SELECT o.id,c.id,v.name,v.sku,v.barcode,v.price,v.cost,v.unit,v.quantity_scale,v.minimum_stock
FROM organizations o JOIN categories c ON c.organization_id=o.id
JOIN (VALUES
 ('Almacén','Arroz 1 kg','ARR-001','7790000000011',89.00,62.00,'UNIT',0,5.000),
 ('Almacén','Pan por kilo','PAN-001','7790000000012',180.00,95.00,'KG',3,2.000),
 ('Almacén','Chocolate','CHO-001','7790000000013',75.00,48.00,'UNIT',0,5.000),
 ('Bebidas','Agua 600 ml','AGU-001','7790000000014',55.00,29.00,'UNIT',0,8.000),
 ('Bebidas','Jugo 1 L','JUG-001','7790000000015',120.00,72.00,'UNIT',0,5.000)
) AS v(category,name,sku,barcode,price,cost,unit,quantity_scale,minimum_stock) ON v.category=c.name
WHERE o.name='Demo Suture Caja'
ON CONFLICT (organization_id,sku) DO NOTHING;
UPDATE products SET stock=CASE WHEN unit='KG' THEN 12.000 ELSE 24.000 END
WHERE organization_id=(SELECT id FROM organizations WHERE name='Demo Suture Caja')
 AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.organization_id=products.organization_id AND m.product_id=products.id);
INSERT INTO stock_movements(organization_id,product_id,actor_id,type,quantity,balance_after,reason)
SELECT p.organization_id,p.id,u.id,'INITIAL',p.stock,p.stock,'Semilla demo'
FROM products p JOIN organizations o ON o.id=p.organization_id JOIN users u ON u.organization_id=o.id AND u.role='ADMINISTRADOR'
WHERE o.name='Demo Suture Caja' AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.organization_id=p.organization_id AND m.product_id=p.id)
 AND u.id=(SELECT MIN(id) FROM users WHERE organization_id=o.id AND role='ADMINISTRADOR');
COMMIT;
