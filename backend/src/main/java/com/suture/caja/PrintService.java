package com.suture.caja;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrintService {
    private final Store store; private final SaleService sales; private final CatalogService catalog; private final ObjectMapper json;
    @Value("${caja.print.url:}") private String url;
    @Value("${caja.print.token:}") private String token;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    PrintService(Store store,SaleService sales,CatalogService catalog,ObjectMapper json) { this.store=store;this.sales=sales;this.catalog=catalog;this.json=json; }

    public Map<String,Object> settings(long org) {
        List<Map<String,Object>> rows=store.many("SELECT destination,printer_id,enabled FROM print_settings WHERE organization_id=?",org);
        return rows.isEmpty()?Map.of("destination","CAJA","enabled",false):rows.getFirst();
    }
    public List<Map<String,Object>> jobs(long org,Long saleId) {
        return store.many("SELECT id,sale_id,job_id,status,attempts,last_error,created_at FROM print_jobs WHERE organization_id=? AND (CAST(? AS BIGINT) IS NULL OR sale_id=?) ORDER BY id DESC LIMIT 100",org,saleId,saleId);
    }
    @Transactional public Map<String,Object> settings(long org,String destination,String printerId,boolean enabled) {
        if (destination==null || destination.isBlank()) throw new IllegalArgumentException("Destino requerido");
        store.run("INSERT INTO print_settings(organization_id,destination,printer_id,enabled) VALUES (?,?,?,?) ON CONFLICT (organization_id) DO UPDATE SET destination=EXCLUDED.destination,printer_id=EXCLUDED.printer_id,enabled=EXCLUDED.enabled",org,destination.trim(),printerId==null||printerId.isBlank()?null:printerId.trim(),enabled);
        return settings(org);
    }
    public Map<String,Object> ticket(long org,long saleId) {
        Map<String,Object> sale=sales.sale(org,saleId);
        Map<String,Object> business=store.one("SELECT name,currency FROM organizations WHERE id=?",org);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("kind","INTERNAL_NON_FISCAL");out.put("legend","COMPROBANTE INTERNO NO FISCAL");
        out.put("business",business.get("name"));out.put("currency",business.get("currency"));
        out.put("sale",sale);
        return out;
    }
    @Transactional public Map<String,Object> reprint(long org,long actor,long saleId) {
        sales.sale(org,saleId);
        Integer count=store.db.queryForObject("SELECT COUNT(*) FROM print_jobs WHERE organization_id=? AND sale_id=?",Integer.class,org,saleId);
        String jobId="caja:org:"+org+":sale:"+saleId+":ticket:reprint:"+(count==null?1:count);
        long id=store.id("INSERT INTO print_jobs(organization_id,sale_id,job_id) VALUES (?,?,?) RETURNING id",org,saleId,jobId);
        catalog.audit(org,actor,"SALE_REPRINT","SALE",saleId,jobId);
        return store.one("SELECT id,job_id,status,attempts FROM print_jobs WHERE organization_id=? AND id=?",org,id);
    }
    @Scheduled(fixedDelay=15000,initialDelay=5000)
    @Transactional public void deliverPending() {
        if (url.isBlank() || token.isBlank()) return;
        URI target=URI.create(url);
        if (!"http".equals(target.getScheme()) || !("127.0.0.1".equals(target.getHost()) || "localhost".equals(target.getHost()))) return;
        List<Map<String,Object>> jobs=store.many("SELECT j.id,j.organization_id,j.sale_id,j.job_id,s.destination,s.printer_id FROM print_jobs j JOIN print_settings s ON s.organization_id=j.organization_id AND s.enabled=TRUE WHERE j.status='PENDING' AND j.attempts<20 ORDER BY j.id LIMIT 10 FOR UPDATE OF j SKIP LOCKED");
        for (Map<String,Object> job:jobs) {
            long id=Store.num(job,"id");
            try {
                String payload=json.writeValueAsString(document(job));
                HttpRequest req=HttpRequest.newBuilder(target.resolve("/print")).timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                int status=client.send(req,HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status==200 || status==202) store.run("UPDATE print_jobs SET status='DELIVERED',attempts=attempts+1,last_error=NULL WHERE id=?",id);
                else store.run("UPDATE print_jobs SET attempts=attempts+1,last_error=? WHERE id=?","Agente respondió HTTP "+status,id);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                store.run("UPDATE print_jobs SET attempts=attempts+1,last_error=? WHERE id=?",e.getClass().getSimpleName()+": "+e.getMessage(),id);
            }
        }
    }
    private Map<String,Object> document(Map<String,Object> job) {
        long org=Store.num(job,"organizationId"), saleId=Store.num(job,"saleId");
        Map<String,Object> ticket=ticket(org,saleId);
        @SuppressWarnings("unchecked") Map<String,Object> sale=(Map<String,Object>)ticket.get("sale");
        List<Map<String,Object>> elements=new ArrayList<>();
        elements.add(Map.of("type","text","value",ticket.get("business"),"align","CENTER","bold",true));
        elements.add(Map.of("type","text","value","COMPROBANTE INTERNO NO FISCAL","align","CENTER"));
        elements.add(Map.of("type","text","value","Venta #"+saleId+"  "+sale.get("createdAt")));
        elements.add(Map.of("type","separator"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)sale.get("items");
        for (Map<String,Object> item:items) {
            elements.add(Map.of("type","text","value",cut(String.valueOf(item.get("productName")),32)));
            elements.add(Map.of("type","text","value",cut("  "+item.get("quantity")+" x "+item.get("unitPrice"),32)));
            elements.add(Map.of("type","text","value","  Importe "+item.get("total")));
        }
        elements.add(Map.of("type","separator"));
        elements.add(Map.of("type","text","value","TOTAL "+ticket.get("currency")+" "+sale.get("total"),"bold",true,"size","DOUBLE"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> payments=(List<Map<String,Object>>)sale.get("payments");
        for (Map<String,Object> payment:payments) elements.add(Map.of("type","text","value",payment.get("method")+" "+payment.get("amount")));
        elements.add(Map.of("type","text","value","Vuelto "+sale.get("changeAmount")));
        elements.add(Map.of("type","cut"));
        Map<String,Object> body=new LinkedHashMap<>();body.put("jobId",job.get("jobId"));
        if (job.get("printerId")!=null) body.put("printerId",job.get("printerId"));
        else body.put("destination",job.get("destination"));
        body.put("copies",1);body.put("document",Map.of("elements",elements));
        return body;
    }
    private String cut(String s,int max) { return s.length()<=max?s:s.substring(0,max); }
}
