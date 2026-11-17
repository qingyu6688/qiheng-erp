package cn.erp.portal;

import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(PortalSecurity.PREFIX)
class PortalController {
    private final PortalSessions sessions;
    private final PortalPurchases purchases;
    private final PortalStatements statements;
    private final PortalStatementPdf pdf;
    private final PortalTickets tickets;

    PortalController(PortalSessions sessions,PortalPurchases purchases,PortalStatements statements,PortalStatementPdf pdf,PortalTickets tickets) {
        this.sessions=sessions;
        this.purchases=purchases;
        this.statements=statements;
        this.pdf=pdf;
        this.tickets=tickets;
    }

    record Profile(String id,String companyName,String customerName,String contactName) {}

    @GetMapping("/auth/csrf")
    ApiResponse<?> csrf() {return ApiResponse.success(Map.of("smsEnabled",sessions.smsEnabled()));}

    @PostMapping("/auth/login")
    ApiResponse<?> login(HttpServletRequest request,HttpServletResponse response,@RequestBody PortalSessions.PasswordLogin input) {
        return ApiResponse.success(profile(sessions.password(request,response,input)));
    }

    @PostMapping("/auth/code/send")
    ApiResponse<?> sendCode(HttpServletRequest request,@RequestBody PortalSessions.CodeRequest input) {
        sessions.sendCode(request,input);
        return ApiResponse.success(Map.of("accepted",true));
    }

    @PostMapping("/auth/code/login")
    ApiResponse<?> loginCode(HttpServletRequest request,HttpServletResponse response,@RequestBody PortalSessions.CodeLogin input) {
        return ApiResponse.success(profile(sessions.code(request,response,input)));
    }

    @GetMapping("/auth/me")
    ApiResponse<?> me(HttpServletRequest request) {return ApiResponse.success(profile(PortalActor.require(request)));}

    @PostMapping("/auth/logout")
    ApiResponse<?> logout(HttpServletRequest request,HttpServletResponse response) {
        sessions.logout(request,response);
        return ApiResponse.success(Map.of("loggedOut",true));
    }

    @GetMapping("/dashboard")
    ApiResponse<?> dashboard(HttpServletRequest request) {return ApiResponse.success(purchases.dashboard(PortalActor.require(request)));}

    @GetMapping("/orders")
    ApiResponse<?> orders(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(PortalValues.page(purchases.orders(PortalActor.require(request),cursor,limit),limit,PortalPurchases.Order::id));
    }

    @GetMapping("/orders/{id}")
    ApiResponse<?> order(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(purchases.order(PortalActor.require(request),id));}

    @GetMapping("/shipments")
    ApiResponse<?> shipments(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(PortalValues.page(purchases.shipments(PortalActor.require(request),cursor,limit),limit,PortalPurchases.Shipment::id));
    }

    @GetMapping("/shipments/{id}")
    ApiResponse<?> shipment(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(purchases.shipment(PortalActor.require(request),id));
    }

    @GetMapping("/invoices")
    ApiResponse<?> invoices(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(PortalValues.page(purchases.invoices(PortalActor.require(request),cursor,limit),limit,PortalPurchases.Invoice::id));
    }

    @GetMapping("/invoices/{id}")
    ApiResponse<?> invoice(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(purchases.invoice(PortalActor.require(request),id));}

    @GetMapping("/invoices/{id}/file")
    ResponseEntity<byte[]> invoiceFile(HttpServletRequest request,@PathVariable long id,@RequestParam(defaultValue="issue") String variant,
                                       @RequestParam(defaultValue="pdf") String format) {
        var file=purchases.file(PortalActor.require(request),id,variant,format);
        return attachment(file.filename(),file.bytes(),MediaType.APPLICATION_OCTET_STREAM);
    }

    @GetMapping("/statements")
    ApiResponse<?> statement(HttpServletRequest request,
                             @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                             @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ApiResponse.success(statements.get(PortalActor.require(request),dateFrom,dateTo));
    }

    @PostMapping("/statements/confirm")
    ApiResponse<?> confirm(HttpServletRequest request,@RequestBody PortalStatements.Confirm input) {
        return ApiResponse.success(statements.confirm(PortalActor.require(request),input));
    }

    @GetMapping("/statements/pdf")
    ResponseEntity<byte[]> pdf(HttpServletRequest request,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateFrom,
                               @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        var actor=PortalActor.require(request);
        var statement=statements.get(actor,dateFrom,dateTo);
        return attachment("statement-"+statement.dateFrom()+"-"+statement.dateTo()+".pdf",pdf.render(actor,statement),MediaType.APPLICATION_PDF);
    }

    @GetMapping("/tickets")
    ApiResponse<?> tickets(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(PortalValues.page(tickets.customerList(PortalActor.require(request),cursor,limit),limit,PortalTickets.Ticket::id));
    }

    @PostMapping("/tickets")
    ApiResponse<?> createTicket(HttpServletRequest request,@RequestBody PortalTickets.NewTicket input) {
        return ApiResponse.success(tickets.create(PortalActor.require(request),input));
    }

    @GetMapping("/tickets/{id}")
    ApiResponse<?> ticket(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(tickets.customerDetail(PortalActor.require(request),id));
    }

    @PostMapping("/tickets/{id}/reply")
    ApiResponse<?> reply(HttpServletRequest request,@PathVariable long id,@RequestBody PortalTickets.NewReply input) {
        return ApiResponse.success(tickets.customerReply(PortalActor.require(request),id,input));
    }

    @PostMapping("/tickets/{id}/rate")
    ApiResponse<?> rate(HttpServletRequest request,@PathVariable long id,@RequestBody PortalTickets.Rating input) {
        return ApiResponse.success(tickets.rate(PortalActor.require(request),id,input));
    }

    private Profile profile(PortalActor actor) {return new Profile(Long.toString(actor.userId()),actor.companyName(),actor.customerName(),actor.contactName());}

    private ResponseEntity<byte[]> attachment(String filename,byte[] data,MediaType media) {
        return ResponseEntity.ok().contentType(media).contentLength(data.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").body(data);
    }
}
