import { Component, HostListener, inject, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { firstValueFrom } from 'rxjs';

type Role = 'ADMINISTRADOR' | 'SUPERVISOR' | 'CAJERO' | string;
type Page = 'venta' | 'dashboard' | 'ventas' | 'productos' | 'inventario' | 'caja' | 'reportes' | 'configuracion';
type Id = string | number;
interface Me { id: Id; username: string; role: Role; organizationId: string; organizationName: string; }
interface Category { id: Id; name: string; }
interface Product { id: Id; name: string; sku?: string; barcode?: string; categoryId?: Id; price: number; cost?: number; unit?: string; quantityScale?: number; stock?: number; currentStock?: number; minimumStock?: number; active?: boolean; }
interface Register { id: Id; name: string; }
interface Shift { id: Id; registerId: Id; openedBy?: Id; status: string; openingAmount?: number; openedAt?: string; closedAt?: string; expectedAmount?: number; countedAmount?: number; difference?: number; }
interface Report { daily?: {day:string,total:number,transactions:number}[]; discounts?: {total:number}[]; cancellations?: {count:number,total:number}[]; payments?: {method:string,total:number}[]; products?: {productName:string,quantity:number,total:number}[]; closures?: unknown[]; }
interface CartLine { product: Product; quantity: number; discount: number; }
interface Payment { method: string; amount: number; tendered?: number; }
interface Sale { id: Id; createdAt?: string; total?: number; status?: string; items?: { productName?: string; name?: string; quantity: number; unitPrice?: number; subtotal?: number; total?: number; }[]; payments?: Payment[]; change?: number; changeAmount?: number; }

@Component({selector:'app-root', standalone:true, imports:[CommonModule, FormsModule], templateUrl:'./app.html', styleUrl:'./app.scss'})
export class App {
  private http = inject(HttpClient);
  private auth = '';
  private pendingSaleKey = crypto.randomUUID();
  readonly me = signal<Me | null>(null);
  readonly page = signal<Page>('venta');
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  readonly products = signal<Product[]>([]);
  readonly categories = signal<Category[]>([]);
  readonly registers = signal<Register[]>([]);
  readonly shifts = signal<Shift[]>([]);
  readonly sales = signal<Sale[]>([]);
  readonly dashboard = signal<any>({});
  readonly report = signal<Report>({});
  readonly inventoryMovements = signal<any[]>([]);
  readonly users = signal<any[]>([]);
  organizationForm = {name:'',currency:'UYU',allowNegativeStock:false};
  printForm = {destination:'CAJA',printerId:'',enabled:false};
  userForm = {username:'',password:'',role:'CAJERO'};
  readonly summary = signal<Record<string, unknown>>({});
  readonly cart = signal<CartLine[]>([]);
  readonly checkoutOpen = signal(false);
  readonly latestSale = signal<Sale | null>(null);
  readonly ticketLegend = signal('COMPROBANTE INTERNO NO FISCAL');
  readonly ticketBusiness = signal('');
  username = ''; password = '';
  search = ''; categoryFilter = '';
  selectedRegisterId = ''; openingAmount = 0; openingNotes = '';
  countedAmount = 0; closingReason = ''; movementType = 'IN'; movementAmount = 0; movementReason = ''; inventoryProductId = ''; inventoryType = 'IN'; inventoryQuantity = 0; inventoryReason = '';
  newCategory = ''; productForm = {name:'',sku:'',barcode:'',categoryId:'',price:0,cost:0,unit:'UNIT',quantityScale:0,minimumStock:0,active:true};
  generalDiscount = 0;
  paymentMethod = 'CASH'; paymentAmount = 0; cashTendered = 0;
  payments: Payment[] = [];
  cancelSaleId: Id | null = null; cancelReason = '';
  reportFrom = new Date().toLocaleDateString('sv-SE'); reportTo = new Date().toLocaleDateString('sv-SE');
  readonly nav: {page:Page; label:string; icon:string}[] = [
    {page:'venta',label:'Punto de venta',icon:'▦'}, {page:'dashboard',label:'Inicio',icon:'◫'},
    {page:'ventas',label:'Ventas',icon:'▤'}, {page:'productos',label:'Productos',icon:'◈'},
    {page:'inventario',label:'Inventario',icon:'◧'}, {page:'caja',label:'Caja y turnos',icon:'▣'}, {page:'reportes',label:'Reportes',icon:'▥'}, {page:'configuracion',label:'Configuración',icon:'⚙'}
  ];
  readonly paymentMethods = [{value:'CASH',label:'Efectivo'},{value:'DEBIT_CARD',label:'Débito'},{value:'CREDIT_CARD',label:'Crédito'},{value:'TRANSFER',label:'Transferencia'},{value:'OTHER',label:'Otro'}];

  quantityStep(scale:unknown):number{return Math.pow(10,-Math.min(3,Math.max(0,Number(scale)||0)));}
  shortId(value:Id):string{return String(value).slice(0,8);}
  cents(value:number):number{return Math.round((value+Number.EPSILON)*100)/100;}
  money(value: unknown): string { const n=Number(value ?? 0); return new Intl.NumberFormat('es-UY',{style:'currency',currency:'UYU',maximumFractionDigits:2}).format(Number.isFinite(n)?n:0); }
  date(value: unknown): string { if(!value) return '—'; return new Intl.DateTimeFormat('es-UY',{dateStyle:'short',timeStyle:'short'}).format(new Date(String(value))); }
  number(value: unknown): number { return Number(value ?? 0); }
  val(key:string): unknown { return this.dashboard()[key]; }
  isManager(): boolean { return ['ADMINISTRADOR','SUPERVISOR','ADMIN','SUPERVISOR_ROLE'].includes(this.me()?.role ?? ''); }
  isAdmin(): boolean { return ['ADMINISTRADOR','ADMIN'].includes(this.me()?.role ?? ''); }
  activeShift(): Shift | undefined { return this.shifts().find(s=>(s.status==='OPEN' || s.status==='ABIERTA') && (this.isManager() || String(s.openedBy)===String(this.me()?.id))); }
  filteredProducts(): Product[] { const q=this.search.trim().toLocaleLowerCase(); return this.products().filter(p=>p.active!==false && (!this.categoryFilter || String(p.categoryId)===this.categoryFilter) && (!q || [p.name,p.sku,p.barcode].some(x=>x?.toLocaleLowerCase().includes(q)))); }
  cartSubtotal(): number { return this.cart().reduce((sum,l)=>sum+this.lineTotal(l),0); }
  lineTotal(l:CartLine): number { return this.cents(Math.max(0,l.quantity*Number(l.product.price)-Number(l.discount||0))); }
  cartTotal(): number { return this.cents(Math.max(0,this.cartSubtotal()-Number(this.generalDiscount||0))); }
  paidTotal(): number { return this.cents(this.payments.reduce((sum,p)=>sum+p.amount,0)); }
  balance(): number { return Math.max(0,Math.round((this.cartTotal()-this.paidTotal())*100)/100); }
  totalChange(): number { return this.cents(this.payments.reduce((sum,p)=>sum+Math.max(0,(p.tendered??p.amount)-p.amount),0)); }
  list<T>(value: T[] | {content?: T[]; items?: T[]; data?: T[]} | null): T[] { if(Array.isArray(value)) return value; return value?.content ?? value?.items ?? value?.data ?? []; }
  private headers(): HttpHeaders { return new HttpHeaders({Authorization:`Basic ${this.auth}`}); }
  private get<T>(path:string): Promise<T> { return firstValueFrom(this.http.get<T>(`/api${path}`,{headers:this.headers()})); }
  private post<T>(path:string,body:unknown): Promise<T> { return firstValueFrom(this.http.post<T>(`/api${path}`,body,{headers:this.headers()})); }
  private handle(err:unknown): void { const e=err as HttpErrorResponse; this.error.set(typeof e?.error?.message==='string'?e.error.message:typeof e?.error?.detail==='string'?e.error.detail:typeof e?.error?.error==='string'?e.error.error:e?.status===401?'Usuario o contraseña incorrectos.':e?.status===403?'No tenés permiso para esta operación.':e?.message||'La operación no pudo completarse.'); }
  private clearMessages(): void {this.error.set('');this.notice.set('');}
  async login(): Promise<void> { this.clearMessages(); this.busy.set(true); this.auth=btoa(unescape(encodeURIComponent(`${this.username}:${this.password}`))); try { this.me.set(await this.get<Me>('/me')); this.password=''; await this.refresh(); } catch(e){this.auth='';this.me.set(null);this.handle(e);} finally {this.busy.set(false);} }
  logout(): void {this.auth='';this.me.set(null);this.cart.set([]);this.sales.set([]);this.password='';this.clearMessages();}
  async refresh(): Promise<void> { if(!this.me())return; this.busy.set(true); try { const [products,categories,registers,shifts]=await Promise.all([this.get<Product[]|{content:Product[]}>('/products'),this.get<Category[]>('/categories'),this.get<Register[]>('/registers'),this.get<Shift[]>('/shifts')]); this.products.set(this.list(products));this.categories.set(this.list(categories));this.registers.set(this.list(registers));this.shifts.set(this.list(shifts)); if(!this.selectedRegisterId)this.selectedRegisterId=String(this.registers()[0]?.id||''); await this.loadPage(); }catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async go(page:Page): Promise<void> { this.page.set(page);this.clearMessages();await this.loadPage(); }
  async loadPage(): Promise<void> { try { if(this.page()==='ventas')this.sales.set(this.list(await this.get<Sale[]>('/sales'))); if(this.page()==='dashboard'||this.page()==='reportes')this.dashboard.set(await this.get<any>('/reports/dashboard')); if(this.page()==='reportes')await this.loadReport(); if(this.page()==='inventario')this.inventoryMovements.set(this.list(await this.get<any[]>('/inventory/movements'))); if(this.page()==='configuracion'&&this.isAdmin()){const [org,print,users]=await Promise.all([this.get<any>('/organization'),this.get<any>('/print-settings'),this.get<any[]>('/users')]);this.organizationForm={name:org.name,currency:org.currency,allowNegativeStock:org.allowNegativeStock};this.printForm={destination:print.destination,printerId:print.printerId||'',enabled:print.enabled};this.users.set(users);} if(this.page()==='caja'&&this.activeShift())this.summary.set(await this.get<Record<string,unknown>>(`/shifts/${this.activeShift()!.id}/summary`)); }catch(e){this.handle(e);} }
  async loadReport():Promise<void>{const params=new URLSearchParams();if(this.reportFrom)params.set('from',this.reportFrom);if(this.reportTo)params.set('to',this.reportTo);try{this.report.set(await this.get<Report>('/reports'+(params.size?'?'+params.toString():'')));}catch(e){this.handle(e);}}
  async adjustStock():Promise<void>{if(!this.inventoryProductId||!this.inventoryReason.trim()||!Number.isFinite(Number(this.inventoryQuantity))){this.error.set('Seleccioná un producto, cantidad y motivo.');return;}this.busy.set(true);this.clearMessages();try{await this.post('/inventory/movements',{productId:Number(this.inventoryProductId),type:this.inventoryType,quantity:Number(this.inventoryQuantity),reason:this.inventoryReason.trim()});this.inventoryQuantity=0;this.inventoryReason='';this.notice.set('Movimiento de inventario registrado.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  add(product:Product): void { if(!this.activeShift()){this.error.set('Abrí una caja antes de vender.');return;}this.clearMessages();this.pendingSaleKey=crypto.randomUUID();const prior=this.cart().find(l=>l.product.id===product.id);this.cart.set(prior?this.cart().map(l=>l===prior?{...l,quantity:l.quantity+1}:l):[...this.cart(),{product,quantity:1,discount:0}]); }
  quantity(productId:Id,raw:unknown): void { const q=Number(raw);const step=this.quantityStep(this.cart().find(l=>l.product.id===productId)?.product.quantityScale);if(!Number.isFinite(q)||q<0||Math.abs(q/step-Math.round(q/step))>0.000001){this.error.set('Cantidad inválida para la unidad del producto.');return;}this.pendingSaleKey=crypto.randomUUID();this.cart.set(this.cart().map(l=>l.product.id===productId?{...l,quantity:q}:l).filter(l=>l.quantity>0)); }
  lineDiscount(productId:Id,raw:unknown):void {const n=Number(raw);if(!Number.isFinite(n)||n<0)return;this.pendingSaleKey=crypto.randomUUID();this.cart.set(this.cart().map(l=>l.product.id===productId?{...l,discount:n}:l));}
  remove(productId:Id): void {this.pendingSaleKey=crypto.randomUUID();this.cart.set(this.cart().filter(l=>l.product.id!==productId));}
  clearCart():void {this.cart.set([]);this.payments=[];this.generalDiscount=0;this.pendingSaleKey=crypto.randomUUID();this.checkoutOpen.set(false);}
  openCheckout():void {if(!this.cart().length||!this.activeShift())return;this.payments=[];this.paymentAmount=this.cartTotal();this.cashTendered=this.paymentAmount;this.checkoutOpen.set(true);this.clearMessages();}
  addPayment():void {let amount=this.cents(Number(this.paymentAmount));const tendered=this.cents(Number(this.cashTendered));if(!Number.isFinite(amount)||amount<=0||amount>this.balance()+0.001){this.error.set('Ingresá un importe válido, no mayor al saldo.');return;}if(this.paymentMethod==='CASH'&&(!Number.isFinite(tendered)||tendered<amount)){this.error.set('El efectivo recibido debe cubrir el importe asignado.');return;}this.pendingSaleKey=crypto.randomUUID();this.payments=[...this.payments,{method:this.paymentMethod,amount,...(this.paymentMethod==='CASH'?{tendered}: {})}];this.paymentAmount=this.balance();this.cashTendered=this.paymentAmount;this.error.set('');}
  removePayment(i:number):void {this.pendingSaleKey=crypto.randomUUID();this.payments=this.payments.filter((_,index)=>index!==i);this.paymentAmount=this.balance();}
  async confirmSale():Promise<void> {const shift=this.activeShift();if(this.busy())return;if(!shift||!this.cart().length||this.balance()>0.001){this.error.set('Completá el pago antes de confirmar.');return;}this.busy.set(true);this.clearMessages();const idempotencyKey=this.pendingSaleKey;try{const sale=await this.post<Sale>('/sales',{idempotencyKey,shiftId:shift.id,items:this.cart().map(l=>({productId:l.product.id,quantity:l.quantity,discount:this.cents(l.discount)})),discount:this.cents(Number(this.generalDiscount||0)),payments:this.payments});this.latestSale.set(sale);this.clearCart();this.notice.set(`Venta ${sale.id} confirmada. El comprobante interno no es fiscal.`);await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async openShift():Promise<void>{if(!this.selectedRegisterId){this.error.set('Seleccioná una caja.');return;}this.busy.set(true);this.clearMessages();try{await this.post('/shifts',{registerId:Number(this.selectedRegisterId),openingAmount:this.cents(Number(this.openingAmount)),notes:this.openingNotes});this.notice.set('Caja abierta. Ya podés vender.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async closeShift():Promise<void>{const shift=this.activeShift();if(!shift)return;this.busy.set(true);this.clearMessages();try{await this.post(`/shifts/${shift.id}/close`,{countedAmount:this.cents(Number(this.countedAmount)),reason:this.closingReason});this.notice.set('Turno cerrado y arqueo registrado.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async addMovement():Promise<void>{const shift=this.activeShift();if(!shift)return;this.busy.set(true);this.clearMessages();try{await this.post(`/shifts/${shift.id}/movements`,{type:this.movementType,amount:this.cents(Number(this.movementAmount)),reason:this.movementReason});this.movementAmount=0;this.movementReason='';this.notice.set('Movimiento registrado.');await this.loadPage();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  discountChanged(value:unknown):void{this.generalDiscount=Number(value)||0;this.pendingSaleKey=crypto.randomUUID();}
  async saveOrganization():Promise<void>{this.busy.set(true);this.clearMessages();try{await firstValueFrom(this.http.put('/api/organization',this.organizationForm,{headers:this.headers()}));this.notice.set('Comercio actualizado.');await this.loadPage();}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  async savePrint():Promise<void>{this.busy.set(true);this.clearMessages();try{await firstValueFrom(this.http.put('/api/print-settings',this.printForm,{headers:this.headers()}));this.notice.set('Configuración de impresión guardada.');}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  async createUser():Promise<void>{this.busy.set(true);this.clearMessages();try{await this.post('/users',this.userForm);this.userForm={username:'',password:'',role:'CAJERO'};this.notice.set('Usuario creado.');await this.loadPage();}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  async createCategory():Promise<void>{if(!this.newCategory.trim())return;this.busy.set(true);this.clearMessages();try{await this.post('/categories',{name:this.newCategory.trim()});this.newCategory='';this.notice.set('Categoría guardada.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async createProduct():Promise<void>{if(!this.productForm.name.trim()||!this.productForm.sku.trim()){this.error.set('Nombre y SKU son obligatorios.');return;}this.busy.set(true);this.clearMessages();try{await this.post('/products',{...this.productForm,categoryId:this.productForm.categoryId?Number(this.productForm.categoryId):null,barcode:this.productForm.barcode||null});this.productForm={name:'',sku:'',barcode:'',categoryId:'',price:0,cost:0,unit:'UNIT',quantityScale:0,minimumStock:0,active:true};this.notice.set('Producto guardado.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async createRegister(name:string):Promise<void>{if(!name.trim())return;this.busy.set(true);this.clearMessages();try{await this.post('/registers',{name:name.trim()});this.notice.set('Caja creada.');await this.refresh();}catch(e){this.handle(e);}finally{this.busy.set(false);} }
  async cancelSale():Promise<void>{if(this.cancelSaleId===null||!this.cancelReason.trim()){this.error.set('Indicá el motivo de anulación.');return;}this.busy.set(true);this.clearMessages();try{await this.post(`/sales/${this.cancelSaleId}/cancel`,{reason:this.cancelReason.trim()});this.cancelSaleId=null;this.cancelReason='';this.notice.set('Venta anulada y movimientos internos revertidos.');await this.loadPage();}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  async reprintSale(id:Id):Promise<void>{this.busy.set(true);this.clearMessages();try{await this.post(`/sales/${id}/reprint`,{});this.notice.set('Reimpresión encolada. Consultá la configuración de impresión para su entrega.');}catch(e){this.handle(e);}finally{this.busy.set(false);}}
  async printSale(id:Id):Promise<void>{this.clearMessages();try{const ticket=await this.get<{legend:string;business:string;sale:Sale}>(`/sales/${id}/ticket`);this.ticketLegend.set(ticket.legend);this.ticketBusiness.set(ticket.business);this.latestSale.set(ticket.sale);setTimeout(()=>window.print(),20);}catch(e){this.handle(e);}}
  printLatest():void{const id=this.latestSale()?.id;if(id)this.printSale(id);}
  @HostListener('document:keydown',['$event']) hotkeys(event:KeyboardEvent):void{const target=event.target as HTMLElement;const typing=['INPUT','TEXTAREA','SELECT'].includes(target?.tagName)||target?.isContentEditable;if(event.key==='Escape'&&this.checkoutOpen()){this.checkoutOpen.set(false);return;}if(!this.me()||this.page()!=='venta'||typing)return;if(event.key==='/'){event.preventDefault();document.querySelector<HTMLInputElement>('#product-search')?.focus();}if(event.key==='F2'){event.preventDefault();this.openCheckout();}}
}
