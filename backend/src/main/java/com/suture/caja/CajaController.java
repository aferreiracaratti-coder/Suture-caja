package com.suture.caja;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class CajaController {
    private final Store store; private final CatalogService catalog; private final CashService cash; private final SaleService sales; private final ReportsService reports; private final PrintService print; private final org.springframework.security.crypto.password.PasswordEncoder encoder;
    CajaController(Store store,CatalogService catalog,CashService cash,SaleService sales,ReportsService reports,PrintService print,org.springframework.security.crypto.password.PasswordEncoder encoder) { this.store=store;this.catalog=catalog;this.cash=cash;this.sales=sales;this.reports=reports;this.print=print;this.encoder=encoder; }
    @GetMapping("/me") public Map<String,Object> me(@AuthenticationPrincipal SecurityConfig.Operator user) {
        Map<String,Object> org=store.one("SELECT name,currency,allow_negative_stock FROM organizations WHERE id=?",user.organizationId());
        return Map.of("id",user.id(),"username",user.username(),"role",user.role(),"organizationId",user.organizationId(),"organizationName",org.get("name"),"currency",org.get("currency"));
    }
    @GetMapping("/organization") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> organization(@AuthenticationPrincipal SecurityConfig.Operator u) { return store.one("SELECT id,name,currency,allow_negative_stock FROM organizations WHERE id=?",u.organizationId()); }
    @PutMapping("/organization") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> organization(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Organization r) {
        if (r.currency().length()!=3) throw new IllegalArgumentException("Moneda ISO de tres letras requerida");
        store.run("UPDATE organizations SET name=?,currency=?,allow_negative_stock=? WHERE id=?",r.name().trim(),r.currency().toUpperCase(),r.allowNegativeStock(),u.organizationId());
        return organization(u);
    }
    @GetMapping("/users") @PreAuthorize("hasRole('ADMINISTRADOR')") public List<Map<String,Object>> users(@AuthenticationPrincipal SecurityConfig.Operator u) { return store.many("SELECT id,username,role,enabled,created_at FROM users WHERE organization_id=? ORDER BY id",u.organizationId()); }
    @PostMapping("/users") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> createUser(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.User r) {
        if (!List.of("ADMINISTRADOR","SUPERVISOR","CAJERO").contains(r.role()) || r.password().length()<12) throw new IllegalArgumentException("Rol o contraseña inválida (mínimo 12 caracteres)");
        long id=store.id("INSERT INTO users(organization_id,username,password_hash,role) VALUES (?,?,?,?) RETURNING id",u.organizationId(),r.username().trim(),encoder.encode(r.password()),r.role());
        return store.one("SELECT id,username,role,enabled FROM users WHERE organization_id=? AND id=?",u.organizationId(),id);
    }
    @GetMapping("/categories") public List<Map<String,Object>> categories(@AuthenticationPrincipal SecurityConfig.Operator u) { return catalog.categories(u.organizationId()); }
    @PostMapping("/categories") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> category(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Category r) { return catalog.createCategory(u.organizationId(),r); }
    @GetMapping("/products") public List<Map<String,Object>> products(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam(required=false) String q,@RequestParam(required=false) Long categoryId) { return catalog.products(u.organizationId(),q,categoryId); }
    @PostMapping("/products") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> product(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Product r) { return catalog.createProduct(u.organizationId(),u.id(),r); }
    @PutMapping("/products/{id}") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> product(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id,@Valid @RequestBody Requests.Product r) { return catalog.updateProduct(u.organizationId(),u.id(),id,r); }
    @GetMapping("/inventory/movements") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public List<Map<String,Object>> inventory(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam(required=false) Long productId) { return catalog.stockHistory(u.organizationId(),productId); }
    @PostMapping("/inventory/movements") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> inventory(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.StockMovement r) { return catalog.stock(u.organizationId(),u.id(),r); }
    @GetMapping("/registers") public List<Map<String,Object>> registers(@AuthenticationPrincipal SecurityConfig.Operator u) { return cash.registers(u.organizationId()); }
    @PostMapping("/registers") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> register(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Register r) { return cash.createRegister(u.organizationId(),r); }
    @GetMapping("/shifts") public List<Map<String,Object>> shifts(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam(required=false) Long registerId) { return cash.shifts(u.organizationId(),registerId); }
    @PostMapping("/shifts") public Map<String,Object> open(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Shift r) { return cash.open(u.organizationId(),u.id(),r); }
    @GetMapping("/shifts/{id}/summary") public Map<String,Object> summary(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id) { return cash.summary(u.organizationId(),id); }
    @GetMapping("/shifts/{id}/movements") public List<Map<String,Object>> movements(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id) { return cash.movements(u.organizationId(),id); }
    @PostMapping("/shifts/{id}/movements") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> movement(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id,@Valid @RequestBody Requests.CashMovement r) { return cash.movement(u.organizationId(),u.id(),id,r); }
    @PostMapping("/shifts/{id}/close") public Map<String,Object> close(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id,@Valid @RequestBody Requests.Close r) { return cash.close(u.organizationId(),u.id(),u.role(),id,r); }
    @GetMapping("/sales") public List<Map<String,Object>> sales(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam(required=false) Long shiftId) { return sales.sales(u.organizationId(),shiftId); }
    @GetMapping("/sales/{id}") public Map<String,Object> sale(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id) { return sales.sale(u.organizationId(),id); }
    @PostMapping("/sales") public Map<String,Object> sale(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.Sale r) { return sales.confirm(u.organizationId(),u.id(),u.role(),r); }
    @PostMapping("/sales/{id}/cancel") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> cancel(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id,@Valid @RequestBody Requests.Cancel r) { return sales.cancel(u.organizationId(),u.id(),id,r.reason()); }
    @GetMapping("/sales/{id}/ticket") public Map<String,Object> ticket(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id) { return print.ticket(u.organizationId(),id); }
    @PostMapping("/sales/{id}/reprint") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> reprint(@AuthenticationPrincipal SecurityConfig.Operator u,@PathVariable long id) { return print.reprint(u.organizationId(),u.id(),id); }
    @GetMapping("/print-settings") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> printSettings(@AuthenticationPrincipal SecurityConfig.Operator u) { return print.settings(u.organizationId()); }
    @GetMapping("/print-jobs") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public List<Map<String,Object>> printJobs(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam(required=false) Long saleId) { return print.jobs(u.organizationId(),saleId); }
    @PutMapping("/print-settings") @PreAuthorize("hasRole('ADMINISTRADOR')") public Map<String,Object> printSettings(@AuthenticationPrincipal SecurityConfig.Operator u,@Valid @RequestBody Requests.PrintSettings r) { return print.settings(u.organizationId(),r.destination(),r.printerId(),r.enabled()); }
    @GetMapping("/reports/dashboard") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> dashboard(@AuthenticationPrincipal SecurityConfig.Operator u) { return reports.dashboard(u.organizationId()); }
    @GetMapping("/reports") @PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')") public Map<String,Object> report(@AuthenticationPrincipal SecurityConfig.Operator u,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(required=false) Long registerId,@RequestParam(required=false) Long cashierId,@RequestParam(required=false) Long categoryId) { return reports.report(u.organizationId(),from,to,registerId,cashierId,categoryId); }
    @GetMapping("/audit") @PreAuthorize("hasRole('ADMINISTRADOR')") public List<Map<String,Object>> audit(@AuthenticationPrincipal SecurityConfig.Operator u) { return store.many("SELECT id,actor_id,action,entity_type,entity_id,detail,created_at FROM audit_events WHERE organization_id=? ORDER BY id DESC LIMIT 500",u.organizationId()); }
}
