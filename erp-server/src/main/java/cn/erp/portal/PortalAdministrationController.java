package cn.erp.portal;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PortalAdministrationController {
    private final AccessService access;
    private final PortalAccounts accounts;
    private final PortalTickets tickets;
    private final PortalTicketAssist assist;

    PortalAdministrationController(AccessService access,PortalAccounts accounts,PortalTickets tickets,PortalTicketAssist assist) {
        this.access=access;
        this.accounts=accounts;
        this.tickets=tickets;
        this.assist=assist;
    }

    @GetMapping("/api/v1/portal-users")
    ApiResponse<?> accounts(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit) {
        var actor=access.requireCompany(request,"portal_user:view");
        return ApiResponse.success(PortalValues.page(accounts.list(actor,PortalValues.cursor(cursor,limit),limit+1),limit,PortalAccounts.Account::id));
    }

    @PostMapping("/api/v1/portal-users")
    ApiResponse<?> createAccount(HttpServletRequest request,@RequestBody PortalAccounts.Create input) {
        return ApiResponse.success(accounts.create(access.requireCompany(request,"portal_user:manage"),input));
    }

    @PutMapping("/api/v1/portal-users/{id}")
    ApiResponse<?> updateAccount(HttpServletRequest request,@PathVariable long id,@RequestBody PortalAccounts.Change input) {
        return ApiResponse.success(accounts.change(access.requireCompany(request,"portal_user:manage"),id,input));
    }

    @GetMapping("/api/v1/cs/tickets")
    ApiResponse<?> tickets(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit,
                           @RequestParam(required=false) String status) {
        var actor=access.requireCompany(request,"cs_ticket:view");
        return ApiResponse.success(PortalValues.page(tickets.staffList(actor,cursor,limit,status),limit,PortalTickets.Ticket::id));
    }

    @GetMapping("/api/v1/cs/tickets/{id}")
    ApiResponse<?> ticket(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(tickets.staffDetail(access.requireCompany(request,"cs_ticket:view"),id));
    }

    @GetMapping("/api/v1/cs/tickets/{id}/assist")
    ApiResponse<?> assist(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(assist.read(access.requireCompany(request,"cs_ticket:view"),id));
    }

    @PostMapping("/api/v1/cs/tickets/{id}/assign")
    ApiResponse<?> assign(HttpServletRequest request,@PathVariable long id,@RequestBody PortalTickets.Assign input) {
        return ApiResponse.success(tickets.assign(operator(request,"assign"),id,input));
    }

    @PostMapping("/api/v1/cs/tickets/{id}/reply")
    ApiResponse<?> reply(HttpServletRequest request,@PathVariable long id,@RequestBody PortalTickets.NewReply input) {
        return ApiResponse.success(tickets.staffReply(operator(request,"reply"),id,input));
    }

    @PostMapping("/api/v1/cs/tickets/{id}/{action:resolve|close}")
    ApiResponse<?> finish(HttpServletRequest request,@PathVariable long id,@PathVariable String action,@RequestBody PortalTickets.Revision input) {
        return ApiResponse.success(tickets.finish(operator(request,action),id,action,input));
    }

    private CompanyAccess operator(HttpServletRequest request,String action) {
        var actor=access.requireCompany(request,"cs_ticket:"+action);
        if(!actor.hasPermission("cs_ticket:view")) throw new ApiException(403,"FORBIDDEN","没有查看客服工单的权限");
        return actor;
    }
}
