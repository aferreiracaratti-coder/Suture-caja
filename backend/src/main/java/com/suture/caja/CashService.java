package com.suture.caja;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.suture.caja.Store.*;

@Service
public class CashService {
    private final Store store;
    private final CatalogService catalog;
    CashService(Store store,CatalogService catalog) { this.store=store;this.catalog=catalog; }

    public List<Map<String,Object>> registers(long org) { return store.many("SELECT id,name,active FROM registers WHERE organization_id=? ORDER BY name",org); }
    @Transactional public Map<String,Object> createRegister(long org,Requests.Register r) {
        long id=store.id("INSERT INTO registers(organization_id,name) VALUES (?,?) RETURNING id",org,r.name().trim());
        return store.one("SELECT id,name,active FROM registers WHERE organization_id=? AND id=?",org,id);
    }
    public List<Map<String,Object>> shifts(long org,Long registerId) {
        return store.many("SELECT id,register_id,opened_by,closed_by,opened_at,closed_at,status,opening_amount,counted_amount,expected_amount,difference_amount,notes,close_reason FROM shifts WHERE organization_id=? AND (CAST(? AS BIGINT) IS NULL OR register_id=?) ORDER BY id DESC LIMIT 200",org,registerId,registerId);
    }
    @Transactional public Map<String,Object> open(long org,long actor,Requests.Shift r) {
        if (r.openingAmount().scale()>2) throw new IllegalArgumentException("Importe inválido");
        store.one("SELECT id FROM registers WHERE organization_id=? AND id=? AND active=TRUE FOR UPDATE",org,r.registerId());
        long id=store.id("INSERT INTO shifts(organization_id,register_id,opened_by,opening_amount,notes) VALUES (?,?,?,?,?) RETURNING id",org,r.registerId(),actor,r.openingAmount(),r.notes());
        catalog.audit(org,actor,"SHIFT_OPENED","SHIFT",id,"Fondo "+r.openingAmount());
        return shift(org,id);
    }
    public Map<String,Object> shift(long org,long id) { return store.one("SELECT id,register_id,opened_by,closed_by,opened_at,closed_at,status,opening_amount,counted_amount,expected_amount,difference_amount,notes,close_reason FROM shifts WHERE organization_id=? AND id=?",org,id); }
    public Map<String,Object> summary(long org,long id) {
        Map<String,Object> shift=shift(org,id);
        BigDecimal net=store.db.queryForObject("SELECT COALESCE(SUM(amount),0) FROM cash_movements WHERE organization_id=? AND shift_id=?",BigDecimal.class,org,id);
        BigDecimal expected=((BigDecimal)shift.get("openingAmount")).add(net);
        Map<String,Object> out=new java.util.LinkedHashMap<>(shift);
        out.put("cashNet",net);out.put("expected",expected);
        out.put("payments",store.many("SELECT p.method,COALESCE(SUM(p.amount),0) AS total FROM payments p JOIN sales s ON s.id=p.sale_id AND s.organization_id=p.organization_id WHERE s.organization_id=? AND s.shift_id=? AND s.status='PAID' GROUP BY p.method ORDER BY p.method",org,id));
        out.put("salesTotal",store.db.queryForObject("SELECT COALESCE(SUM(total),0) FROM sales WHERE organization_id=? AND shift_id=? AND status='PAID'",BigDecimal.class,org,id));
        return out;
    }
    @Transactional public Map<String,Object> movement(long org,long actor,long shiftId,Requests.CashMovement r) {
        if (!r.type().equals("IN") && !r.type().equals("OUT")) throw new IllegalArgumentException("Movimiento debe ser IN u OUT");
        if (r.amount().scale()>2) throw new IllegalArgumentException("Importe inválido");
        openShift(org,shiftId);
        BigDecimal signed=r.type().equals("OUT")?r.amount().negate():r.amount();
        long id=store.id("INSERT INTO cash_movements(organization_id,shift_id,actor_id,type,amount,reason) VALUES (?,?,?,?,?,?) RETURNING id",org,shiftId,actor,r.type(),signed,r.reason().trim());
        catalog.audit(org,actor,"CASH_"+r.type(),"SHIFT",shiftId,r.reason());
        return store.one("SELECT id,shift_id,actor_id,type,amount,reason,created_at FROM cash_movements WHERE organization_id=? AND id=?",org,id);
    }
    @Transactional public Map<String,Object> close(long org,long actor,String role,long id,Requests.Close r) {
        if (r.countedAmount().scale()>2) throw new IllegalArgumentException("Importe inválido");
        Map<String,Object> shift=openShift(org,id);
        if (role.equals("CAJERO") && num(shift,"openedBy")!=actor) throw new ApiException.Business(HttpStatus.FORBIDDEN,"Solo puede cerrar su propio turno");
        Map<String,Object> summary=summary(org,id);
        BigDecimal expected=(BigDecimal)summary.get("expected");
        BigDecimal difference=r.countedAmount().subtract(expected);
        if (difference.signum()!=0 && (r.reason()==null || r.reason().isBlank())) throw new IllegalArgumentException("La diferencia requiere motivo");
        store.run("UPDATE shifts SET status='CLOSED',closed_by=?,closed_at=CURRENT_TIMESTAMP,counted_amount=?,expected_amount=?,difference_amount=?,close_reason=? WHERE organization_id=? AND id=?",actor,r.countedAmount(),expected,difference,r.reason(),org,id);
        catalog.audit(org,actor,"SHIFT_CLOSED","SHIFT",id,"Esperado="+expected+", contado="+r.countedAmount()+", diferencia="+difference+", motivo="+r.reason());
        return summary(org,id);
    }
    public Map<String,Object> openShift(long org,long id) {
        Map<String,Object> s=store.one("SELECT id,register_id,status,opened_by FROM shifts WHERE organization_id=? AND id=? FOR UPDATE",org,id);
        if (!"OPEN".equals(s.get("status"))) throw new ApiException.Business(HttpStatus.CONFLICT,"La caja está cerrada");
        return s;
    }
    public List<Map<String,Object>> movements(long org,long id) {
        shift(org,id);
        return store.many("SELECT id,shift_id,actor_id,sale_id,type,amount,reason,created_at FROM cash_movements WHERE organization_id=? AND shift_id=? ORDER BY id DESC",org,id);
    }
}
