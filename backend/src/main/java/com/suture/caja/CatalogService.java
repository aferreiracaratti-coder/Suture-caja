package com.suture.caja;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.suture.caja.Store.*;

@Service
public class CatalogService {
    private final Store store;
    CatalogService(Store store) { this.store=store; }

    public List<Map<String,Object>> categories(long org) { return store.many("SELECT id,name FROM categories WHERE organization_id=? ORDER BY name",org); }
    @Transactional public Map<String,Object> createCategory(long org, Requests.Category request) {
        long id=store.id("INSERT INTO categories(organization_id,name) VALUES (?,?) RETURNING id",org,request.name().trim());
        return store.one("SELECT id,name FROM categories WHERE organization_id=? AND id=?",org,id);
    }
    public List<Map<String,Object>> products(long org, String q, Long categoryId) {
        String term=q==null?"":q.trim();
        return store.many("SELECT id,category_id,name,description,sku,barcode,price,cost,unit,quantity_scale,active,stock,minimum_stock,image_url FROM products WHERE organization_id=? AND (?='' OR lower(name) LIKE lower(?) OR lower(sku) LIKE lower(?) OR barcode=?) AND (CAST(? AS BIGINT) IS NULL OR category_id=?) ORDER BY name LIMIT 200",org,term,"%"+term+"%","%"+term+"%",term,categoryId,categoryId);
    }
    @Transactional public Map<String,Object> createProduct(long org, long actor, Requests.Product p) {
        validateProduct(p); category(org,p.categoryId());
        long id=store.id("INSERT INTO products(organization_id,category_id,name,description,sku,barcode,price,cost,unit,quantity_scale,active,minimum_stock,image_url) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",org,p.categoryId(),p.name().trim(),p.description(),p.sku().trim(),blankNull(p.barcode()),p.price(),p.cost(),p.unit().trim(),p.quantityScale(),p.active(),p.minimumStock(),p.imageUrl());
        audit(org,actor,"PRODUCT_CREATED","PRODUCT",id,p.name());
        return product(org,id);
    }
    @Transactional public Map<String,Object> updateProduct(long org,long actor,long id,Requests.Product p) {
        validateProduct(p); category(org,p.categoryId());
        Map<String,Object> old=product(org,id);
        store.run("UPDATE products SET category_id=?,name=?,description=?,sku=?,barcode=?,price=?,cost=?,unit=?,quantity_scale=?,active=?,minimum_stock=?,image_url=?,version=version+1 WHERE organization_id=? AND id=?",p.categoryId(),p.name().trim(),p.description(),p.sku().trim(),blankNull(p.barcode()),p.price(),p.cost(),p.unit().trim(),p.quantityScale(),p.active(),p.minimumStock(),p.imageUrl(),org,id);
        if (((BigDecimal)old.get("price")).compareTo(p.price())!=0) audit(org,actor,"PRICE_CHANGED","PRODUCT",id,old.get("price")+" → "+p.price());
        return product(org,id);
    }
    public Map<String,Object> product(long org,long id) { return store.one("SELECT id,category_id,name,description,sku,barcode,price,cost,unit,quantity_scale,active,stock,minimum_stock,image_url FROM products WHERE organization_id=? AND id=?",org,id); }
    @Transactional public Map<String,Object> stock(long org,long actor,Requests.StockMovement m) {
        if (!List.of("INITIAL","IN","OUT","ADJUST").contains(m.type())) throw new IllegalArgumentException("Tipo de movimiento inválido");
        if (m.quantity().compareTo(BigDecimal.ZERO)==0 || m.quantity().scale()>3) throw new IllegalArgumentException("Cantidad inválida");
        Map<String,Object> p=store.one("SELECT id,stock,quantity_scale FROM products WHERE organization_id=? AND id=? FOR UPDATE",org,m.productId());
        if (m.quantity().scale()>((Number)p.get("quantityScale")).intValue()) throw new IllegalArgumentException("Precisión de cantidad inválida");
        BigDecimal delta=m.quantity();
        if (m.type().equals("OUT")) delta=delta.abs().negate();
        if (m.type().equals("IN") || m.type().equals("INITIAL")) delta=delta.abs();
        boolean negative=Boolean.TRUE.equals(store.one("SELECT allow_negative_stock FROM organizations WHERE id=?",org).get("allowNegativeStock"));
        BigDecimal balance=((BigDecimal)p.get("stock")).add(delta);
        if (balance.signum()<0 && !negative) throw new ApiException.Business(HttpStatus.CONFLICT,"Stock insuficiente");
        store.run("UPDATE products SET stock=?,version=version+1 WHERE organization_id=? AND id=?",balance,org,m.productId());
        long id=store.id("INSERT INTO stock_movements(organization_id,product_id,actor_id,type,quantity,balance_after,reason) VALUES (?,?,?,?,?,?,?) RETURNING id",org,m.productId(),actor,m.type(),delta,balance,m.reason().trim());
        audit(org,actor,"STOCK_"+m.type(),"PRODUCT",m.productId(),m.reason());
        return store.one("SELECT id,product_id,type,quantity,balance_after,reason,created_at FROM stock_movements WHERE organization_id=? AND id=?",org,id);
    }
    public List<Map<String,Object>> stockHistory(long org,Long productId) {
        return store.many("SELECT id,product_id,sale_id,actor_id,type,quantity,balance_after,reason,created_at FROM stock_movements WHERE organization_id=? AND (CAST(? AS BIGINT) IS NULL OR product_id=?) ORDER BY id DESC LIMIT 500",org,productId,productId);
    }
    void audit(long org,long actor,String action,String type,long id,String detail) {
        store.run("INSERT INTO audit_events(organization_id,actor_id,action,entity_type,entity_id,detail) VALUES (?,?,?,?,?,?)",org,actor,action,type,id,detail);
    }
    private void category(long org,Long id) { if (id!=null) store.one("SELECT id FROM categories WHERE organization_id=? AND id=?",org,id); }
    private void validateProduct(Requests.Product p) {
        if (p.price().scale()>2 || p.cost().scale()>2 || p.minimumStock().scale()>3) throw new IllegalArgumentException("Precisión inválida");
        if (p.unit().length()>20) throw new IllegalArgumentException("Unidad demasiado larga");
    }
    private String blankNull(String s) { return s==null||s.isBlank()?null:s.trim(); }
}
