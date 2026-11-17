package cn.erp.openapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.catalog.OpenCatalog;
import cn.erp.fulfillment.ShipmentQueries;
import cn.erp.masterdata.WarehouseService;
import cn.erp.purchasefinance.PaymentService;
import cn.erp.purchasefinance.TreasuryModels;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderQueries;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingQueries;
import cn.erp.reference.OpenPartners;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.ReceiptService;
import cn.erp.salesorder.SalesInventoryQueries;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiResponse;
import static cn.erp.openapi.OpenModels.*;
import static cn.erp.openapi.OpenSecurity.actor;
import static cn.erp.openapi.OpenSecurity.commandKey;

@RestController
@RequestMapping("/open/v1")
class OpenController {
    private final OpenCatalog catalog;
    private final OpenPartners partners;
    private final WarehouseService warehouses;
    private final SalesInventoryQueries inventory;
    private final SalesOrderQueries salesReads;
    private final SalesOrderService sales;
    private final PurchaseOrderQueries purchaseReads;
    private final PurchaseOrderService purchases;
    private final ShipmentQueries shipments;
    private final ReceivingQueries receiving;
    private final ReceiptService receipts;
    private final PaymentService payments;

    OpenController(OpenCatalog catalog,OpenPartners partners,WarehouseService warehouses,SalesInventoryQueries inventory,
            SalesOrderQueries salesReads,SalesOrderService sales,PurchaseOrderQueries purchaseReads,PurchaseOrderService purchases,
            ShipmentQueries shipments,ReceivingQueries receiving,ReceiptService receipts,PaymentService payments) {
        this.catalog=catalog;
        this.partners=partners;
        this.warehouses=warehouses;
        this.inventory=inventory;
        this.salesReads=salesReads;
        this.sales=sales;
        this.purchaseReads=purchaseReads;
        this.purchases=purchases;
        this.shipments=shipments;
        this.receiving=receiving;
        this.receipts=receipts;
        this.payments=payments;
    }

    @GetMapping("/skus")
    ApiResponse<OpenCatalog.Page> skus(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String status,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(catalog.list(actor(req,"sku:view"),keyword,status,limit,cursor));
    }

    @GetMapping("/skus/{id}")
    ApiResponse<OpenCatalog.Sku> sku(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(catalog.get(actor(req,"sku:view"),id));
    }

    @PostMapping("/skus")
    ResponseEntity<ApiResponse<OpenCatalog.Sku>> createSku(HttpServletRequest req,@Valid @RequestBody SkuInput input) {
        return created(catalog.create(actor(req,"sku:create"),commandKey(req),OpenPolicy.id(input.productId()),
                input.code(),input.name(),input.spec(),input.extJson()));
    }

    @GetMapping("/partners")
    ApiResponse<OpenPartners.Page> partners(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String role,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(partners.list(actor(req,"partner:view"),keyword,role,cursor,limit));
    }

    @GetMapping("/partners/{id}")
    ApiResponse<OpenPartners.Partner> partner(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(partners.get(actor(req,"partner:view"),id));
    }

    @PostMapping("/partners")
    ResponseEntity<ApiResponse<OpenPartners.Partner>> createPartner(HttpServletRequest req,@Valid @RequestBody PartnerInput input) {
        return created(partners.create(actor(req,"partner:create"),commandKey(req),input.code(),input.name(),input.shortName(),input.roles(),input.extJson()));
    }

    @GetMapping("/warehouses")
    ApiResponse<WarehouseService.WarehousePage> warehouseList(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(warehouses.openPage(actor(req,"warehouse:view"),keyword,limit,cursor));
    }

    @GetMapping("/inventory-balances")
    ApiResponse<Page<Stock>> balances(HttpServletRequest req,@RequestParam(required=false) String warehouseId,
            @RequestParam(defaultValue="") String keyword,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        var result=inventory.balances(actor(req,"inventory:view"),warehouseId,keyword,cursor,limit,false,false);
        return ApiResponse.success(page(result,row -> new Stock(text(row,"skuId"),text(row,"warehouseId"),text(row,"availableQty"))));
    }

    @GetMapping("/sales-orders")
    ApiResponse<Page<Document>> salesList(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String status,@RequestParam(required=false) String customerId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(page(salesReads.list(actor(req,"sales_order:view"),keyword,status,customerId,cursor,limit),OpenModels::document));
    }

    @GetMapping("/sales-orders/{id}")
    ApiResponse<Document> sale(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(document(salesReads.get(actor(req,"sales_order:view"),id)));
    }

    @PostMapping("/sales-orders")
    ResponseEntity<ApiResponse<Document>> createSale(HttpServletRequest req,@Valid @RequestBody OrderInput input) {
        var principal=actor(req,"sales_order:create");
        OpenPolicy.id(input.customerId());
        var lines=input.lines().stream().map(line -> new SalesOrderModels.Line(null,checked(line.skuId()),checked(line.warehouseId()),
                checked(line.unitId()),line.qty(),line.unitPrice(),null,null,null,line.taxRate(),line.extJson())).toList();
        var order=new SalesOrderModels.Input(input.customerId(),input.businessDate(),input.deliveryDate(),input.priceTaxInclusive(),
                null,lines,List.of(),input.remark(),null,null,null,null,null,input.extJson());
        return created(document(sales.create(principal,commandKey(req),order)));
    }

    @PostMapping("/sales-orders/{id}/submit")
    ApiResponse<Document> submitSale(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Command input) {
        return saleCommand(req,id,"submit",input);
    }

    @PostMapping("/sales-orders/{id}/cancel")
    ApiResponse<Document> cancelSale(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Command input) {
        return saleCommand(req,id,"cancel",input);
    }

    private ApiResponse<Document> saleCommand(HttpServletRequest req,long id,String action,Command input) {
        var scope=actor(req,"sales_order:"+action);
        return ApiResponse.success(document(sales.command(scope,id,action,commandKey(req),
                new SalesOrderModels.Command(input.version(),input.comment(),input.reason()))));
    }

    @GetMapping("/purchase-orders")
    ApiResponse<Page<Document>> purchaseList(HttpServletRequest req,@RequestParam(defaultValue="") String keyword,
            @RequestParam(required=false) String status,@RequestParam(required=false) String supplierId,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(page(purchaseReads.list(actor(req,"purchase_order:view"),keyword,status,supplierId,cursor,limit),OpenModels::document));
    }

    @GetMapping("/purchase-orders/{id}")
    ApiResponse<Document> purchase(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(document(purchaseReads.get(actor(req,"purchase_order:view"),id)));
    }

    @PostMapping("/purchase-orders")
    ResponseEntity<ApiResponse<Document>> createPurchase(HttpServletRequest req,@Valid @RequestBody OrderInput input) {
        var principal=actor(req,"purchase_order:create");
        OpenPolicy.id(input.supplierId());
        var lines=input.lines().stream().map(line -> new PurchaseOrderModels.Line(null,null,checked(line.skuId()),checked(line.warehouseId()),
                checked(line.unitId()),line.qty(),line.unitPrice(),null,null,null,line.taxRate())).toList();
        var order=new PurchaseOrderModels.Input(input.supplierId(),input.businessDate(),input.deliveryDate(),input.priceTaxInclusive(),
                null,lines,List.of(),input.remark(),null,null,null,input.extJson());
        return created(document(purchases.create(principal,commandKey(req),order)));
    }

    @PostMapping("/purchase-orders/{id}/submit")
    ApiResponse<Document> submitPurchase(HttpServletRequest req,@PathVariable long id,@Valid @RequestBody Command input) {
        var scope=actor(req,"purchase_order:submit");
        return ApiResponse.success(document(purchases.command(scope,id,"submit",commandKey(req),
                new PurchaseOrderModels.Command(input.version(),input.comment(),input.reason()))));
    }

    @GetMapping("/shipments")
    ApiResponse<Page<Document>> shipmentList(HttpServletRequest req,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long customerId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(page(shipments.list(actor(req,"shipment:view"),status,false,customerId,cursor,limit),OpenModels::document));
    }

    @GetMapping("/shipments/{id}")
    ApiResponse<Document> shipment(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(document(shipments.get(actor(req,"shipment:view"),id)));
    }

    @GetMapping("/goods-receipts")
    ApiResponse<Page<Document>> receiptList(HttpServletRequest req,@RequestParam(required=false) String status,
            @RequestParam(required=false) Long supplierId,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponse.success(page(receiving.list(actor(req,"goods_receipt:view"),false,supplierId,status,cursor,limit),OpenModels::document));
    }

    @GetMapping("/goods-receipts/{id}")
    ApiResponse<Document> received(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(document(receiving.get(actor(req,"goods_receipt:view"),false,id)));
    }

    @GetMapping("/receipts/{id}")
    ApiResponse<Document> receipt(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(document(receipts.get(actor(req,"receipt:view"),id)));
    }

    @PostMapping("/receipts")
    ResponseEntity<ApiResponse<Document>> receivePayment(HttpServletRequest req,@Valid @RequestBody FinanceDraft input) {
        var scope=actor(req,"receipt:create");
        return created(document(receipts.create(scope,commandKey(req),new FinanceModels.Receipt("customer",
                OpenPolicy.id(input.partnerId()),null,OpenPolicy.id(input.cashAccountId()),input.amount(),
                input.businessDate(),null,null,input.remark(),null))));
    }

    @PostMapping("/payments")
    ResponseEntity<ApiResponse<Document>> paySupplier(HttpServletRequest req,@Valid @RequestBody FinanceDraft input) {
        var scope=actor(req,"payment:create");
        return created(document(payments.create(scope,commandKey(req),new TreasuryModels.Payment("supplier",
                OpenPolicy.id(input.partnerId()),null,OpenPolicy.id(input.cashAccountId()),input.amount(),
                input.businessDate(),null,null,input.remark(),null))));
    }

    private static String checked(String id) { OpenPolicy.id(id);return id; }

    private static <T> ResponseEntity<ApiResponse<T>> created(T value) {
        return ResponseEntity.status(201).body(ApiResponse.created(value));
    }
}
