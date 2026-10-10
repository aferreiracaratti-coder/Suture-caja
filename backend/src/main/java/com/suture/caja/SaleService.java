package com.suture.caja;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.suture.caja.Store.*;

@Service
public class SaleService {
    private static final List<String> METHODS=List.of("CASH","DEBIT_CARD","CREDIT_CARD","TRANSFER","OTHER");
    private final Store store;
    private final CashService cash;
    private final CatalogService catalog;
    SaleService(Store store,CashService cash,CatalogService catalog) { this.store=store;this.cash=cash;this.catalog=catalog; }

    public List<Map<String,Object>> sales(long org,Long shiftId) {
        return store.many("SELECT id,shift_id,cashier_id,idempotency_key,status,subtotal,line_discount,discount,total,change_amount,created_at,cancelled_at,cancel_reason FROM sales WHERE organization_id=? AND (CAST(? AS BIGINT) IS NULL OR shift_id=?) ORDER BY id DESC LIMIT 300",org,shiftId,shiftId);
    }
    public Map<String,Object> sale(long org,long id) {
        Map<String,Object> sale=new java.util.LinkedHashMap<>(store.one("SELECT id,shift_id,cashier_id,idempotency_key,status,subtotal,line_discount,discount,total,change_amount,created_at,cancelled_at,cancel_reason,fiscal_document_id FROM sales WHERE organization_id=? AND id=?",org,id));
        sale.put("items",store.many("SELECT id,product_id,product_name,sku,quantity,unit_price,unit_cost,discount,order_discount,total FROM sale_items WHERE organization_id=? AND sale_id=? ORDER BY id",org,id));
        sale.put("payments",store.many("SELECT id,method,amount,tendered,change_amount,external_status FROM payments WHERE organization_id=? AND sale_id=? ORDER BY id",org,id));
        return sale;
    }
    @Transactional public Map<String,Object> confirm(long org,long actor,String role,Requests.Sale r) {
        // Serializes tenant confirmations and makes an idempotency retry observe the committed sale.
        store.one("SELECT id FROM organizations WHERE id=? FOR UPDATE",org);
        String requestHash=fingerprint(r);
        List<Map<String,Object>> existing=store.many("SELECT id,cashier_id,request_hash FROM sales WHERE organization_id=? AND idempotency_key=?",org,r.idempotencyKey());
        if (!existing.isEmpty()) {
            if (num(existing.getFirst(),"cashierId")!=actor) throw new ApiException.Business(HttpStatus.CONFLICT,"Clave idempotente utilizada por otro cajero");
            if (!requestHash.equals(existing.getFirst().get("requestHash"))) throw new ApiException.Business(HttpStatus.CONFLICT,"Clave idempotente reutilizada con otro contenido");
            return sale(org,num(existing.getFirst(),"id"));
        }
        Map<String,Object> shift=cash.openShift(org,r.shiftId());
        if (role.equals("CAJERO") && num(shift,"openedBy")!=actor) throw new ApiException.Business(HttpStatus.FORBIDDEN,"Solo puede vender en su turno");
        if (r.items().size()>100 || r.payments().size()>10) throw new IllegalArgumentException("Demasiadas líneas o pagos");
        if (r.discount().scale()>2) throw new IllegalArgumentException("Descuento inválido");
        boolean discounted=r.discount().signum()>0;
        BigDecimal subtotal=BigDecimal.ZERO,lineDiscount=BigDecimal.ZERO;
        List<Line> lines=new ArrayList<>();
        // Lock products in stable order, then calculate prices and stock using server-side values.
        List<Long> productIds=r.items().stream().map(Requests.Item::productId).distinct().sorted().toList();
        Map<Long,Map<String,Object>> locked=new java.util.HashMap<>();
        for (long productId:productIds) locked.put(productId,store.one("SELECT id,name,sku,price,cost,quantity_scale,active,stock FROM products WHERE organization_id=? AND id=? FOR UPDATE",org,productId));
        Map<Long,BigDecimal> quantities=new java.util.HashMap<>();
        for (Requests.Item item:r.items()) {
            Map<String,Object> p=locked.get(item.productId());
            if (!Boolean.TRUE.equals(p.get("active"))) throw new ApiException.Business(HttpStatus.CONFLICT,"Producto inactivo: "+p.get("name"));
            if (item.quantity().scale()>((Number)p.get("quantityScale")).intValue()) throw new IllegalArgumentException("Precisión de cantidad inválida: "+p.get("name"));
            if (item.discount().scale()>2) throw new IllegalArgumentException("Descuento inválido");
            BigDecimal price=money(p,"price");
            BigDecimal base=price.multiply(item.quantity()).setScale(2,RoundingMode.HALF_UP);
            if (item.discount().compareTo(base)>0) throw new IllegalArgumentException("Descuento superior al subtotal");
            subtotal=subtotal.add(base);lineDiscount=lineDiscount.add(item.discount());
            if (item.discount().signum()>0) discounted=true;
            quantities.merge(item.productId(),item.quantity(),BigDecimal::add);
            lines.add(new Line(item,p,base.subtract(item.discount())));
        }
        if (discounted && role.equals("CAJERO")) throw new ApiException.Business(HttpStatus.FORBIDDEN,"Descuento requiere supervisor");
        BigDecimal beforeOrderDiscount=subtotal.subtract(lineDiscount);
        if (r.discount().compareTo(beforeOrderDiscount)>0) throw new IllegalArgumentException("Descuento superior al subtotal");
        BigDecimal total=beforeOrderDiscount.subtract(r.discount());
        if (total.signum()<=0) throw new IllegalArgumentException("La venta debe tener total positivo");
        BigDecimal paid=BigDecimal.ZERO, change=BigDecimal.ZERO,cashNet=BigDecimal.ZERO;
        for (Requests.Payment payment:r.payments()) {
            if (!METHODS.contains(payment.method()) || payment.amount().scale()>2) throw new IllegalArgumentException("Medio o importe inválido");
            paid=paid.add(payment.amount());
            if (payment.method().equals("CASH")) {
                BigDecimal tendered=payment.tendered()==null?payment.amount():payment.tendered();
                if (tendered.scale()>2 || tendered.compareTo(payment.amount())<0) throw new IllegalArgumentException("Efectivo entregado insuficiente");
                change=change.add(tendered.subtract(payment.amount()));
                cashNet=cashNet.add(payment.amount());
            } else if (payment.tendered()!=null) throw new IllegalArgumentException("Importe entregado solo aplica a efectivo");
        }
        if (paid.compareTo(total)!=0) throw new IllegalArgumentException("Los pagos deben igualar el total calculado: "+total);
        boolean allowNegative=Boolean.TRUE.equals(store.one("SELECT allow_negative_stock FROM organizations WHERE id=?",org).get("allowNegativeStock"));
        for (var entry:quantities.entrySet()) {
            Map<String,Object> p=locked.get(entry.getKey());
            if (!allowNegative && money(p,"stock").compareTo(entry.getValue())<0)
                throw new ApiException.Business(HttpStatus.CONFLICT,"Stock insuficiente: "+p.get("name"));
        }
        long saleId=store.id("INSERT INTO sales(organization_id,shift_id,cashier_id,idempotency_key,request_hash,status,subtotal,line_discount,discount,total,change_amount) VALUES (?,?,?,?,?,'PAID',?,?,?,?,?) RETURNING id",org,r.shiftId(),actor,r.idempotencyKey(),requestHash,subtotal,lineDiscount,r.discount(),total,change);
        List<BigDecimal> allocations=MoneyAllocation.byWeights(r.discount(),lines.stream().map(Line::total).toList());
        for (int i=0;i<lines.size();i++) {
            Line line=lines.get(i);
            Requests.Item item=line.item();Map<String,Object> p=line.product();
            BigDecimal allocated=allocations.get(i);
            store.run("INSERT INTO sale_items(organization_id,sale_id,product_id,product_name,sku,quantity,unit_price,unit_cost,discount,order_discount,total) VALUES (?,?,?,?,?,?,?,?,?,?,?)",org,saleId,item.productId(),p.get("name"),p.get("sku"),item.quantity(),p.get("price"),p.get("cost"),item.discount(),allocated,line.total().subtract(allocated));
        }
        for (var entry:quantities.entrySet()) {
            Map<String,Object> p=locked.get(entry.getKey());BigDecimal next=money(p,"stock").subtract(entry.getValue());
            store.run("UPDATE products SET stock=?,version=version+1 WHERE organization_id=? AND id=?",next,org,entry.getKey());
            store.run("INSERT INTO stock_movements(organization_id,product_id,sale_id,actor_id,type,quantity,balance_after,reason) VALUES (?,?,?,?,'SALE',?,?,'Venta')",org,entry.getKey(),saleId,actor,entry.getValue().negate(),next);
        }
        for (Requests.Payment payment:r.payments()) {
            BigDecimal tendered=payment.method().equals("CASH")?(payment.tendered()==null?payment.amount():payment.tendered()):null;
            BigDecimal paymentChange=tendered==null?BigDecimal.ZERO:tendered.subtract(payment.amount());
            store.run("INSERT INTO payments(organization_id,sale_id,method,amount,tendered,change_amount,external_status) VALUES (?,?,?,?,?,?,?)",org,saleId,payment.method(),payment.amount(),tendered,paymentChange,payment.method().equals("CASH")?"NOT_APPLICABLE":"DECLARED");
        }
        if (cashNet.signum()>0) store.run("INSERT INTO cash_movements(organization_id,shift_id,actor_id,sale_id,type,amount,reason) VALUES (?,?,?,?,'SALE_CASH',?,'Cobro efectivo neto de vuelto')",org,r.shiftId(),actor,saleId,cashNet);
        catalog.audit(org,actor,"SALE_PAID","SALE",saleId,"Total="+total+", vuelto="+change);
        if (discounted) catalog.audit(org,actor,"SALE_DISCOUNT","SALE",saleId,"Líneas="+lineDiscount+", general="+r.discount());
        store.run("INSERT INTO print_jobs(organization_id,sale_id,job_id) VALUES (?,?,?)",org,saleId,"caja:org:"+org+":sale:"+saleId+":ticket:v1");
        return sale(org,saleId);
    }
    @Transactional public Map<String,Object> cancel(long org,long actor,long id,String reason) {
        if (reason==null || reason.isBlank()) throw new IllegalArgumentException("Motivo obligatorio");
        Map<String,Object> sale=store.one("SELECT id,shift_id,status,fiscal_document_id FROM sales WHERE organization_id=? AND id=? FOR UPDATE",org,id);
        if (!"PAID".equals(sale.get("status"))) throw new ApiException.Business(HttpStatus.CONFLICT,"Venta ya anulada");
        if (sale.get("fiscalDocumentId")!=null) throw new ApiException.Business(HttpStatus.CONFLICT,"Venta fiscalizada: requiere nota de crédito externa");
        List<Map<String,Object>> payments=store.many("SELECT method,amount FROM payments WHERE organization_id=? AND sale_id=?",org,id);
        BigDecimal cashRefund=payments.stream().filter(p->"CASH".equals(p.get("method"))).map(p->money(p,"amount")).reduce(BigDecimal.ZERO,BigDecimal::add);
        Long refundShift=null;
        if (cashRefund.signum()>0) {
            long originalShift=num(sale,"shiftId");
            long registerId=num(store.one("SELECT register_id FROM shifts WHERE organization_id=? AND id=?",org,originalShift),"registerId");
            List<Map<String,Object>> open=store.many("SELECT id FROM shifts WHERE organization_id=? AND register_id=? AND status='OPEN' FOR UPDATE",org,registerId);
            if (open.isEmpty()) throw new ApiException.Business(HttpStatus.CONFLICT,"Abra la misma caja para registrar devolución de efectivo");
            refundShift=num(open.getFirst(),"id");
        }
        List<Map<String,Object>> items=store.many("SELECT product_id,quantity FROM sale_items WHERE organization_id=? AND sale_id=? ORDER BY product_id",org,id);
        Map<Long,BigDecimal> quantities=new java.util.TreeMap<>();
        items.forEach(item->quantities.merge(num(item,"productId"),money(item,"quantity"),BigDecimal::add));
        for (var entry:quantities.entrySet()) {
            Map<String,Object> product=store.one("SELECT stock FROM products WHERE organization_id=? AND id=? FOR UPDATE",org,entry.getKey());
            BigDecimal next=money(product,"stock").add(entry.getValue());
            store.run("UPDATE products SET stock=?,version=version+1 WHERE organization_id=? AND id=?",next,org,entry.getKey());
            store.run("INSERT INTO stock_movements(organization_id,product_id,sale_id,actor_id,type,quantity,balance_after,reason) VALUES (?,?,?,?,'CANCEL_SALE',?,?,?)",org,entry.getKey(),id,actor,entry.getValue(),next,reason.trim());
        }
        if (cashRefund.signum()>0) store.run("INSERT INTO cash_movements(organization_id,shift_id,actor_id,sale_id,type,amount,reason) VALUES (?,?,?,?,'CANCEL_CASH',?,?)",org,refundShift,actor,id,cashRefund.negate(),reason.trim());
        store.run("UPDATE sales SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,cancelled_by=?,cancel_reason=? WHERE organization_id=? AND id=?",actor,reason.trim(),org,id);
        catalog.audit(org,actor,"SALE_CANCELLED","SALE",id,reason.trim()+". Pagos electrónicos declarados requieren gestión externa.");
        return sale(org,id);
    }
    private record Line(Requests.Item item,Map<String,Object> product,BigDecimal total) {}
    private String fingerprint(Requests.Sale r) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((r.shiftId()+"|"+r.items()+"|"+r.discount()+"|"+r.payments()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
