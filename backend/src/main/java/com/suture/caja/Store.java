package com.suture.caja;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
    final JdbcTemplate db;
    Store(JdbcTemplate db) { this.db = db; }

    public Map<String,Object> one(String sql, Object... args) {
        List<Map<String,Object>> rows = db.queryForList(sql, args);
        if (rows.isEmpty()) throw new ApiException.Business(HttpStatus.NOT_FOUND, "No se encontró el recurso");
        return camel(rows.getFirst());
    }
    public List<Map<String,Object>> many(String sql, Object... args) { return db.queryForList(sql,args).stream().map(Store::camel).toList(); }
    public Long id(String sql, Object... args) { return db.queryForObject(sql, Long.class, args); }
    public void run(String sql, Object... args) { db.update(sql,args); }
    public static long num(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    public static BigDecimal money(Map<String,Object> row, String key) { return (BigDecimal)row.get(key); }
    public static Map<String,Object> camel(Map<String,Object> row) {
        Map<String,Object> out = new LinkedHashMap<>();
        row.forEach((k,v) -> {
            StringBuilder name = new StringBuilder(); boolean upper = false;
            for (char ch : k.toCharArray()) { if (ch=='_') upper=true; else { name.append(upper?Character.toUpperCase(ch):Character.toLowerCase(ch)); upper=false; } }
            out.put(name.toString(), v);
        });
        return out;
    }
}
