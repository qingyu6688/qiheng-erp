package cn.erp.sysadmin;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 只生成浏览器跳转地址，不访问配置的远程站点，不允许在地址里携带凭据。 */
@Component
public class GrafanaLinks {
    private static final Logger LOG=LoggerFactory.getLogger(GrafanaLinks.class);
    private final SystemConfiguration configurations;private final ObjectMapper json;
    public GrafanaLinks(SystemConfiguration configurations,ObjectMapper json) {this.configurations=configurations;this.json=json;}
    public String request(long tenant,String requestId) {
        try {return build(configurations.get(tenant,"observability.grafana_url",""),requestId,json);}
        catch(RuntimeException failure) {LOG.warn("链路查询地址暂不可用，异常类型 {}",failure.getClass().getSimpleName());return null;}
    }
    static String build(String configured,String requestId,ObjectMapper json) {
        if(configured.isBlank()||requestId==null||!requestId.matches("[a-f0-9-]{1,64}")) return null;
        try {
            var base=URI.create(configured.strip());if(!java.util.Set.of("http","https").contains(base.getScheme())||base.getHost()==null||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getRawFragment()!=null) return null;
            var query=Map.of("refId","A","datasource",Map.of("uid","loki","type","loki"),"expr","{job=\"erp\"} |= \""+requestId+"\"");
            String panes=json.writeValueAsString(Map.of("a",Map.of("datasource","loki","queries",java.util.List.of(query),"range",Map.of("from","now-24h","to","now"))));
            return configured.strip().replaceAll("/+$","")+"/explore?schemaVersion=1&orgId=1&panes="+URLEncoder.encode(panes,StandardCharsets.UTF_8);
        } catch(IllegalArgumentException failure) {return null;}
    }
}
