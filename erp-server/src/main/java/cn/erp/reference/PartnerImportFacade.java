package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import jakarta.validation.Validator;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerImportFacade {
    public record Input(String code,String name,String shortName,String taxNo,Set<String> roles,tools.jackson.databind.JsonNode extJson) {
        public Input(String code,String name,String shortName,String taxNo,Set<String> roles) {this(code,name,shortName,taxNo,roles,null);}
    }
    private final PartnerService service;
    private final Validator validator;
    public PartnerImportFacade(PartnerService service,Validator validator){this.service=service;this.validator=validator;}
    @Transactional
    public String create(CompanyAccess scope,Input input) {
        if(!scope.hasPermission("partner:create"))throw new ApiException(403,"FORBIDDEN","没有创建往来单位的权限");
        var value=new PartnerModels.Partner(input.code(),input.name(),input.shortName(),input.taxNo(),input.roles(),null,null,null,input.extJson());
        var errors=validator.validate(value);
        if(!errors.isEmpty()) {
            var error=errors.stream().sorted(java.util.Comparator.comparing(v->v.getPropertyPath().toString())).findFirst().orElseThrow();
            throw new ApiException(400,"VALIDATION_FAILED",error.getMessage(),java.util.Map.of("columnName",error.getPropertyPath().toString().split("\\[")[0]));
        }
        return service.create(scope,value).get("id").toString();
    }
}
