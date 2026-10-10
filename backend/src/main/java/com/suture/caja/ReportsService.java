package com.suture.caja;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ReportsService {
    private final Store store;
    ReportsService(Store store) { this.store=store; }

    public Map<String,Object> dashboard(long org) {
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("today",store.one("SELECT COALESCE(SUM(total),0) AS sales_total,COUNT(*) AS transactions,COALESCE(AVG(total),0) AS average_ticket FROM sales WHERE organization_id=? AND status='PAID' AND created_at >= CURRENT_DATE AND created_at < CURRENT_DATE + INTERVAL '1 day'",org));
        out.put("byPayment",store.many("SELECT p.method,COALESCE(SUM(p.amount),0) AS total FROM payments p JOIN sales s ON s.id=p.sale_id AND s.organization_id=p.organization_id WHERE s.organization_id=? AND s.status='PAID' AND s.created_at >= CURRENT_DATE AND s.created_at < CURRENT_DATE + INTERVAL '1 day' GROUP BY p.method ORDER BY p.method",org));
        out.put("topProducts",store.many("SELECT i.product_id,i.product_name,SUM(i.quantity) AS quantity,SUM(i.total) AS total FROM sale_items i JOIN sales s ON s.id=i.sale_id AND s.organization_id=i.organization_id WHERE s.organization_id=? AND s.status='PAID' AND s.created_at >= CURRENT_DATE AND s.created_at < CURRENT_DATE + INTERVAL '1 day' GROUP BY i.product_id,i.product_name ORDER BY quantity DESC LIMIT 10",org));
        out.put("openShifts",store.many("SELECT id,register_id,opened_at,opening_amount FROM shifts WHERE organization_id=? AND status='OPEN' ORDER BY id",org));
        out.put("lowStock",store.many("SELECT id,name,sku,stock,minimum_stock FROM products WHERE organization_id=? AND active=TRUE AND stock<=minimum_stock ORDER BY stock LIMIT 20",org));
        return out;
    }
    public Map<String,Object> report(long org,LocalDate from,LocalDate to,Long registerId,Long cashierId,Long categoryId) {
        if (from==null || to==null || to.isBefore(from) || to.isAfter(from.plusYears(1))) throw new IllegalArgumentException("Rango de fechas inválido");
        Map<String,Object> out=new LinkedHashMap<>();
        Object[] args={org,from,to.plusDays(1),registerId,registerId,cashierId,cashierId};
        String scope=" FROM sales s JOIN shifts sh ON sh.id=s.shift_id AND sh.organization_id=s.organization_id WHERE s.organization_id=? AND s.created_at>=? AND s.created_at<? AND (CAST(? AS BIGINT) IS NULL OR sh.register_id=?) AND (CAST(? AS BIGINT) IS NULL OR s.cashier_id=?)";
        out.put("daily",store.many("SELECT CAST(s.created_at AS date) AS day,COUNT(*) AS transactions,COALESCE(SUM(s.total),0) AS total"+scope+" AND s.status='PAID' GROUP BY CAST(s.created_at AS date) ORDER BY day",args));
        out.put("discounts",store.many("SELECT COALESCE(SUM(s.line_discount+s.discount),0) AS total"+scope+" AND s.status='PAID'",args));
        out.put("cancellations",store.many("SELECT COUNT(*) AS count,COALESCE(SUM(s.total),0) AS total"+scope+" AND s.status='CANCELLED'",args));
        out.put("payments",store.many("SELECT p.method,COALESCE(SUM(p.amount),0) AS total FROM payments p JOIN sales s ON s.id=p.sale_id AND s.organization_id=p.organization_id JOIN shifts sh ON sh.id=s.shift_id AND sh.organization_id=s.organization_id WHERE s.organization_id=? AND s.created_at>=? AND s.created_at<? AND (CAST(? AS BIGINT) IS NULL OR sh.register_id=?) AND (CAST(? AS BIGINT) IS NULL OR s.cashier_id=?) AND s.status='PAID' GROUP BY p.method ORDER BY p.method",args));
        out.put("products",store.many("SELECT i.product_id,i.product_name,SUM(i.quantity) AS quantity,SUM(i.total) AS total FROM sale_items i JOIN sales s ON s.id=i.sale_id AND s.organization_id=i.organization_id JOIN shifts sh ON sh.id=s.shift_id AND sh.organization_id=s.organization_id LEFT JOIN products pr ON pr.id=i.product_id AND pr.organization_id=i.organization_id WHERE s.organization_id=? AND s.created_at>=? AND s.created_at<? AND (CAST(? AS BIGINT) IS NULL OR sh.register_id=?) AND (CAST(? AS BIGINT) IS NULL OR s.cashier_id=?) AND s.status='PAID' AND (CAST(? AS BIGINT) IS NULL OR pr.category_id=?) GROUP BY i.product_id,i.product_name ORDER BY quantity DESC LIMIT 50",org,from,to.plusDays(1),registerId,registerId,cashierId,cashierId,categoryId,categoryId));
        out.put("closures",store.many("SELECT id,register_id,opened_at,closed_at,expected_amount,counted_amount,difference_amount FROM shifts WHERE organization_id=? AND closed_at>=? AND closed_at<? AND (CAST(? AS BIGINT) IS NULL OR register_id=?) AND status='CLOSED' ORDER BY closed_at DESC",org,from,to.plusDays(1),registerId,registerId));
        return out;
    }
}
