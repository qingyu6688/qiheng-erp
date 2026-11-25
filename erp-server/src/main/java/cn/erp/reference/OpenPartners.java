package cn.erp.reference;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.BusinessCommands;

/** 开放平台往来单位响应省去税号、联系人与内部操作状态。 */
@Service
public class OpenPartners {
    public record Partner(String id,String code,String name,String status,List<String> roles,Map<String,cn.erp.shared.PaymentTerms.Term> paymentTerms) {}
    public record Page(List<Partner> items,String nextCursor) {}
    private final PartnerService partners;
    private final BusinessCommands commands;

    OpenPartners(PartnerService partners,BusinessCommands commands) {
        this.partners=partners;
        this.commands=commands;
    }

    @SuppressWarnings("unchecked")
    public Page list(CompanyAccess actor,String keyword,String role,String cursor,int limit) {
        var page=partners.list(actor,keyword,role,cursor,limit);
        var rows=(List<Map<String,Object>>)page.get("items");
        return new Page(rows.stream().map(OpenPartners::view).toList(),(String)page.get("nextCursor"));
    }

    public Partner get(CompanyAccess actor,long id) { return view(partners.get(actor,id,false)); }

    public Partner create(CompanyAccess actor,String key,String code,String name,String shortName,Set<String> roles,
            tools.jackson.databind.JsonNode ext) {return create(actor,key,code,name,shortName,roles,ext,null);}

    @SuppressWarnings({"unchecked","rawtypes"})
    public Partner create(CompanyAccess actor,String key,String code,String name,String shortName,Set<String> roles,
            tools.jackson.databind.JsonNode ext,Map<String,cn.erp.shared.PaymentTerms.Term> paymentTerms) {
        var input=new PartnerModels.Partner(code,name,shortName,null,roles,null,null,null,ext,paymentTerms);
        Map<String,Object> result=(Map<String,Object>)commands.execute(actor,"partner.create",key,input,201,
                () -> partners.create(actor,input),(Class)Map.class);
        return view(result);
    }

    @SuppressWarnings("unchecked")
    private static Partner view(Map<String,Object> row) {
        return new Partner(row.get("id").toString(),row.get("code").toString(),row.get("name").toString(),
                row.get("status").toString(),(List<String>)row.get("roles"),(Map<String,cn.erp.shared.PaymentTerms.Term>)row.get("paymentTerms"));
    }
}
