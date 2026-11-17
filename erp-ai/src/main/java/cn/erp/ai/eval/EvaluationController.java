package cn.erp.ai.eval;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.ApiResponse;

@RestController
@RequestMapping("/ai/v1/admin")
class EvaluationController {
    private final AiRequest requests;
    private final EvaluationService eval;

    EvaluationController(AiRequest requests,EvaluationService eval) {this.requests=requests;this.eval=eval;}

    @GetMapping("/evals")
    ApiResponse<?> sets(HttpServletRequest r) { return ApiResponse.ok(eval.sets(requests.actor(r))); }

    @GetMapping("/evals/{id}/runs")
    ApiResponse<?> runs(HttpServletRequest r,@PathVariable String id) { return ApiResponse.ok(eval.runs(requests.actor(r),id)); }

    @GetMapping("/evals/{id}/cases")
    ApiResponse<?> cases(HttpServletRequest r,@PathVariable String id) { return ApiResponse.ok(eval.cases(requests.actor(r),id)); }

    @GetMapping("/eval-runs/{id}")
    ApiResponse<?> run(HttpServletRequest r,@PathVariable String id) { return ApiResponse.ok(eval.detail(requests.actor(r),id)); }

    @PostMapping("/evals/{id}/run")
    ApiResponse<?> start(HttpServletRequest r,@PathVariable String id,
            @RequestBody(required=false) EvaluationService.StartInput input) {
        return ApiResponse.ok(eval.start(requests.access(r),id,input));
    }
}
