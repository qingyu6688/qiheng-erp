package cn.erp.openapi;

import java.util.List;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
class OpenDocumentation {
    @Bean
    OpenApiCustomizer internalApiPaths() {
        // 全局路径属性会覆盖分组路径，因此仅在默认规范上保留内部业务路径。
        return api -> {
            if (api.getPaths()!=null) api.getPaths().keySet().removeIf(path -> !path.startsWith("/api/v1/"));
        };
    }

    @Bean
    GroupedOpenApi openApiGroup() {
        return GroupedOpenApi.builder().group("open").pathsToMatch("/open/v1/**")
                .addOpenApiCustomizer(api -> {
                    api.setInfo(new Info().title("柒衡 ERP 开放接口").version("1.0")
                            .description("应用独立签名。写请求需要幂等键；单据复核、确认和过账由 ERP 操作人员完成。"));
                    api.setServers(List.of(new Server().url("/")));
                    if (api.getPaths()==null) return;
                    api.getPaths().forEach((path,item) -> item.readOperationsMap().forEach((method,operation) -> {
                        for (String header:List.of("X-App-Key","X-Timestamp","X-Nonce","X-Company-Id","X-Signature")) {
                            operation.addParametersItem(new HeaderParameter().name(header).required(true).schema(new StringSchema()));
                        }
                        if (method.name().equals("POST")) operation.addParametersItem(new HeaderParameter().name("Idempotency-Key")
                                .required(true).schema(new StringSchema()));
                    }));
                }).build();
    }
}
