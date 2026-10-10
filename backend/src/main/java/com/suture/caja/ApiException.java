package com.suture.caja;

import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiException {
    public static class Business extends RuntimeException {
        private final HttpStatus status;
        public Business(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }
    @ExceptionHandler(Business.class) ResponseEntity<Map<String,String>> business(Business e) { return ResponseEntity.status(e.status()).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class}) ResponseEntity<Map<String,String>> bad(Exception e) { return ResponseEntity.badRequest().body(Map.of("error",e.getMessage())); }
    @ExceptionHandler(DataIntegrityViolationException.class) ResponseEntity<Map<String,String>> conflict(DataIntegrityViolationException e) { return ResponseEntity.status(409).body(Map.of("error","Conflicto de datos o valor duplicado")); }
}
