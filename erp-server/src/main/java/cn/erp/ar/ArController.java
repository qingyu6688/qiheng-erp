package cn.erp.ar;

import java.time.LocalDate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.*;
import cn.erp.shared.*;

@RestController
@RequestMapping("/api/v1/ar")
class ArController {
    private final AccessService access;
    private final ArQueries queries;
    private final CreditAdministration credit;
    private final AgingService aging;
    private final CollectionService collections;
    private final BadDebtService badDebt;

    ArController(
            AccessService access,
            ArQueries queries,
            CreditAdministration credit,
            AgingService aging,
            CollectionService collections,
            BadDebtService badDebt) {
        this.access = access;
        this.queries = queries;
        this.credit = credit;
        this.aging = aging;
        this.collections = collections;
        this.badDebt = badDebt;
    }

    @GetMapping("/credit-limits")
    ApiResponse<?> limits(HttpServletRequest r) {
        return ApiResponse.success(credit.limits(actor(r)));
    }

    @PostMapping("/credit-limits")
    ApiResponse<?> addLimit(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreditAdministration.Limit input) {
        return ApiResponse.success(credit.save(actor(r), null, key, input));
    }

    @PutMapping("/credit-limits/{id}")
    ApiResponse<?> saveLimit(
            HttpServletRequest r,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreditAdministration.Limit input) {
        return ApiResponse.success(credit.save(actor(r), id, key, input));
    }

    @GetMapping("/credit-limits/exposure/{id}")
    ApiResponse<?> exposure(HttpServletRequest r, @PathVariable long id) {
        return ApiResponse.success(credit.exposure(actor(r), id));
    }

    @GetMapping("/credit-increases")
    ApiResponse<?> increases(HttpServletRequest r) {
        return ApiResponse.success(credit.increases(actor(r)));
    }

    @GetMapping("/credit-increases/{id}")
    ApiResponse<?> increase(HttpServletRequest r, @PathVariable long id) {
        return ApiResponse.success(credit.increase(actor(r), id));
    }

    @PostMapping("/credit-increases")
    ApiResponse<?> createIncrease(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreditAdministration.Increase input) {
        return ApiResponse.success(credit.createIncrease(actor(r), key, input));
    }

    @PostMapping("/credit-increases/{id}/{action}")
    ApiResponse<?> changeIncrease(
            HttpServletRequest r,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreditAdministration.Command input) {
        return ApiResponse.success(credit.changeIncrease(actor(r), id, action, key, input));
    }

    @GetMapping("/aging-config")
    ApiResponse<?> aging(HttpServletRequest r) {
        return ApiResponse.success(aging.get(actor(r)));
    }

    @PutMapping("/aging-config")
    ApiResponse<?> saveAging(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody AgingService.Config input) {
        return ApiResponse.success(aging.save(actor(r), key, input));
    }

    @GetMapping("/collection-plans")
    ApiResponse<?> plans(HttpServletRequest r) {
        return ApiResponse.success(collections.list(actor(r)));
    }

    @GetMapping("/collection-items")
    ApiResponse<?> items(HttpServletRequest r) {
        return ApiResponse.success(collections.items(actor(r)));
    }

    @GetMapping("/collection-plans/{id}")
    ApiResponse<?> plan(HttpServletRequest r, @PathVariable long id) {
        return ApiResponse.success(collections.get(actor(r), id));
    }

    @PostMapping("/collection-plans")
    ApiResponse<?> createPlan(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CollectionService.Plan input) {
        return ApiResponse.success(collections.create(actor(r), key, input));
    }

    @PostMapping("/collection-plans/{id}/followups")
    ApiResponse<?> follow(
            HttpServletRequest r,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CollectionService.Followup input) {
        return ApiResponse.success(collections.follow(actor(r), id, key, input));
    }

    @GetMapping("/bad-debts")
    ApiResponse<?> badDebts(HttpServletRequest r) {
        return ApiResponse.success(badDebt.list(actor(r)));
    }

    @GetMapping("/bad-debts/items")
    ApiResponse<?> badDebtItems(HttpServletRequest r) {
        return ApiResponse.success(queries.badDebtItems(actor(r)));
    }

    @GetMapping("/bad-debts/estimate")
    ApiResponse<?> estimate(
            HttpServletRequest r, @RequestParam long customerId, @RequestParam LocalDate asOf) {
        return ApiResponse.success(badDebt.estimate(actor(r), customerId, asOf));
    }

    @PostMapping("/bad-debts")
    ApiResponse<?> createBadDebt(
            HttpServletRequest r,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody BadDebtService.Input input) {
        return ApiResponse.success(badDebt.create(actor(r), key, input));
    }

    @PostMapping("/bad-debts/{id}/{action}")
    ApiResponse<?> changeBadDebt(
            HttpServletRequest r,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody BadDebtService.Command input) {
        return ApiResponse.success(badDebt.change(actor(r), id, action, key, input));
    }

    @GetMapping("/metadata")
    ApiResponse<?> metadata(HttpServletRequest r) {
        return ApiResponse.success(queries.metadata(actor(r)));
    }

    private CompanyAccess actor(HttpServletRequest r) {
        return access.requireCompany(r, null);
    }
}
