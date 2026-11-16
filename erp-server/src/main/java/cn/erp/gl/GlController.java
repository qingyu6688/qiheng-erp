package cn.erp.gl;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.*;
import cn.erp.period.PeriodService;
import cn.erp.shared.*;

@RestController
@RequestMapping("/api/v1/gl")
class GlController {
    private final AccessService access;
    private final GlBookService books;
    private final GlMasterService master;
    private final GlVoucherService vouchers;
    private final GlQueryService queries;
    private final GlAdministration administration;
    private final PeriodService periods;

    GlController(
            AccessService access,
            GlBookService books,
            GlMasterService master,
            GlVoucherService vouchers,
            GlQueryService queries,
            GlAdministration administration,
            PeriodService periods) {
        this.access = access;
        this.books = books;
        this.master = master;
        this.vouchers = vouchers;
        this.queries = queries;
        this.administration = administration;
        this.periods = periods;
    }

    @GetMapping("/book")
    ApiResponse<?> book(HttpServletRequest r) {
        return ApiResponse.success(books.get(actor(r)));
    }

    @GetMapping("/presets")
    ApiResponse<?> presets(HttpServletRequest r) {
        return ApiResponse.success(books.presets(actor(r)));
    }

    @PostMapping("/book")
    ApiResponse<?> create(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Book input) {
        return ApiResponse.success(books.create(actor(r), key, input));
    }

    @PostMapping("/book/confirm")
    ApiResponse<?> confirm(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Confirmation input) {
        return ApiResponse.success(books.confirm(actor(r), key, input));
    }

    @PostMapping("/book/{action:activate|deactivate}")
    ApiResponse<?> activate(
            HttpServletRequest r,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Version input) {
        return ApiResponse.success(books.activate(actor(r), action, key, input));
    }

    @GetMapping("/config/{kind}")
    ApiResponse<?> config(HttpServletRequest r, @PathVariable String kind) {
        return ApiResponse.success(master.list(actor(r), kind));
    }

    @PostMapping("/config/{kind}")
    ApiResponse<?> addConfig(
            HttpServletRequest r,
            @PathVariable String kind,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> input) {
        return ApiResponse.success(master.save(actor(r), kind, null, key, input));
    }

    @PutMapping("/config/{kind}/{id}")
    ApiResponse<?> editConfig(
            HttpServletRequest r,
            @PathVariable String kind,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Map<String, Object> input) {
        return ApiResponse.success(master.save(actor(r), kind, id, key, input));
    }

    @GetMapping("/opening")
    ApiResponse<?> opening(HttpServletRequest r) {
        return ApiResponse.success(books.openings(actor(r)));
    }

    @PutMapping("/opening")
    ApiResponse<?> openingSave(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Openings input) {
        return ApiResponse.success(books.saveOpenings(actor(r), key, input));
    }

    @GetMapping("/opening/trial")
    ApiResponse<?> trial(HttpServletRequest r) {
        return ApiResponse.success(books.trial(actor(r)));
    }

    @GetMapping("/opening/suggest")
    ApiResponse<?> suggest(HttpServletRequest r) {
        return ApiResponse.success(books.suggest(actor(r)));
    }

    @GetMapping("/vouchers")
    ApiResponse<?> list(HttpServletRequest r, @ModelAttribute GlModels.VoucherFilter query) {
        return ApiResponse.success(vouchers.list(actor(r), query));
    }

    @GetMapping("/vouchers/{id}")
    ApiResponse<?> voucher(HttpServletRequest r, @PathVariable long id) {
        return ApiResponse.success(vouchers.get(actor(r), id));
    }

    @PostMapping("/vouchers")
    ApiResponse<?> addVoucher(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Voucher input) {
        return ApiResponse.success(vouchers.save(actor(r), null, key, input));
    }

    @PutMapping("/vouchers/{id}")
    ApiResponse<?> editVoucher(
            HttpServletRequest r,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Voucher input) {
        return ApiResponse.success(vouchers.save(actor(r), id, key, input));
    }

    @PostMapping("/vouchers/{id}/{action:submit|withdraw|approve|reject|post|delete}")
    ApiResponse<?> change(
            HttpServletRequest r,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Version input) {
        return ApiResponse.success(vouchers.change(actor(r), id, action, key, input));
    }

    @PostMapping("/vouchers/{id}/reverse")
    ApiResponse<?> reverse(
            HttpServletRequest r,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody GlVoucherService.Reversal input) {
        return ApiResponse.success(vouchers.reverse(actor(r), id, key, input));
    }

    @PostMapping("/vouchers/batch/{action}")
    ApiResponse<?> batch(
            HttpServletRequest r,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody GlModels.Batch input) {
        return ApiResponse.success(vouchers.batch(actor(r), action, key, input));
    }

    record Organize(long periodId, long wordId) {}

    @PostMapping("/vouchers/organize")
    ApiResponse<?> organize(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Organize input) {
        return ApiResponse.success(
                vouchers.organize(actor(r), input.periodId(), input.wordId(), key));
    }

    @GetMapping("/ledgers/{type}")
    ApiResponse<?> ledger(
            HttpServletRequest r, @PathVariable String type, @ModelAttribute GlModels.Query query) {
        return ApiResponse.success(queries.ledger(actor(r), type, query));
    }

    @GetMapping({"/reports/{type}", "/statements/{type}"})
    ApiResponse<?> statement(
            HttpServletRequest r, @PathVariable String type, @RequestParam long periodId) {
        return ApiResponse.success(queries.statement(actor(r), type, periodId));
    }

    @GetMapping("/indicators")
    ApiResponse<?> indicators(HttpServletRequest r, @RequestParam long periodId) {
        return ApiResponse.success(queries.indicators(actor(r), periodId));
    }

    @GetMapping("/sync/errors")
    ApiResponse<?> syncErrors(HttpServletRequest r) {
        return ApiResponse.success(administration.errors(actor(r)));
    }

    record Sync(long periodId) {}

    @PostMapping("/sync")
    ApiResponse<?> sync(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Sync input) {
        return ApiResponse.success(administration.synchronize(actor(r), key, input.periodId()));
    }

    @GetMapping("/closing/{id}")
    ApiResponse<?> closeCheck(HttpServletRequest r, @PathVariable long id) {
        var a = access.requireCompany(r, "period:close");
        FinancialScope.require(a, "gl_closing:close");
        return ApiResponse.success(periods.closeCheck(a, id));
    }

    @PostMapping("/closing/{id}/{action:close|reopen}")
    ApiResponse<?> close(
            HttpServletRequest r,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody PeriodService.Version input) {
        var a = access.requireCompany(r, "period:" + action);
        FinancialScope.require(a, "gl_closing:" + action);
        return ApiResponse.success(
                action.equals("close")
                        ? periods.close(a, id, key, input)
                        : periods.reopen(a, id, key, input));
    }

    private CompanyAccess actor(HttpServletRequest r) {
        return access.requireCompany(r, null);
    }

    @org.springframework.beans.factory.annotation.Autowired private GlChoices choices;

    @GetMapping("/choices")
    ApiResponse<?> choices(HttpServletRequest r) {
        return ApiResponse.success(choices.choices(actor(r)));
    }

    @org.springframework.beans.factory.annotation.Autowired private GlPrintService printing;

    @GetMapping("/print/{type}")
    ApiResponse<?> print(
            HttpServletRequest r,
            @PathVariable String type,
            @RequestParam(required = false) Long id,
            @ModelAttribute GlModels.Query query) {
        return ApiResponse.success(printing.snapshot(actor(r), type, id, query));
    }
}
