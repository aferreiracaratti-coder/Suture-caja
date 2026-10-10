import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it, beforeEach } from 'vitest';
import { App } from './app';

describe('Suture Caja POS', () => {
  let app: App;
  beforeEach(() => {
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});
    app=TestBed.runInInjectionContext(()=>new App());
    app.shifts.set([{id:'shift-1',registerId:'reg-1',status:'OPEN'}]);
    app.products.set([{id:'p-1',name:'Cuaderno',sku:'C-1',barcode:'1234',price:125,active:true},{id:'p-2',name:'Lápiz',sku:'L-1',price:50,active:true}]);
  });
  it('busca por nombre, SKU y código de barras', () => {
    app.search='cuad'; expect(app.filteredProducts().map(p=>p.id)).toEqual(['p-1']);
    app.search='L-1'; expect(app.filteredProducts().map(p=>p.id)).toEqual(['p-2']);
    app.search='1234'; expect(app.filteredProducts().map(p=>p.id)).toEqual(['p-1']);
  });
  it('agrega productos, cambia cantidades y calcula el total visible', () => {
    app.add(app.products()[0]); app.quantity('p-1',2); app.add(app.products()[1]);
    expect(app.cartTotal()).toBe(300);
    app.remove('p-2'); expect(app.cartTotal()).toBe(250);
  });
  it('mantiene la línea mientras se reemplaza una cantidad', () => {
    app.add(app.products()[0]);
    app.quantity('p-1','');
    expect(app.cart()).toHaveLength(1);
    app.quantity('p-1',2);
    expect(app.cartTotal()).toBe(250);
  });
  it('registra pagos mixtos y calcula vuelto sólo sobre efectivo', () => {
    app.add(app.products()[0]); app.quantity('p-1',2);
    app.openCheckout(); app.paymentMethod='CASH'; app.paymentAmount=100; app.cashTendered=120; app.addPayment();
    app.paymentMethod='DEBIT_CARD'; app.paymentAmount=150; app.addPayment();
    expect(app.balance()).toBe(0);
    expect(app.totalChange()).toBe(20);
    expect(app.payments).toEqual([{method:'CASH',amount:100,tendered:120},{method:'DEBIT_CARD',amount:150}]);
  });
  it('impide vender con caja cerrada', () => {
    app.shifts.set([]); app.add(app.products()[0]);
    expect(app.cart()).toHaveLength(0); expect(app.error()).toMatch(/Abrí una caja/);
  });
});
