package cn.erp.ar;

import java.math.BigDecimal;
import java.util.*;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

@Service
@Transactional(readOnly = true)
public class AgingService implements AgingPolicyPort {
    private final ArSupport s;

    AgingService(ArSupport s) {
        this.s = s;
    }

    public record Config(
            @NotNull(message = "请提供版本号") Long version,
            @NotNull(message = "请提供账龄上限") List<Integer> bounds,
            @NotNull(message = "请提供估计比例") List<String> rates) {}

    public List<Integer> bounds(ActorScope a) {
        return read(a).bounds();
    }

    Config read(ActorScope a) {
        var rows =
                s.jdbc.queryForList(
                        "SELECT * FROM ar_aging_config WHERE tenant_id=? AND company_id=?",
                        a.tenantId(),
                        a.companyId());
        if (rows.isEmpty())
            return new Config(
                    0L, List.of(30, 60, 90, 180), List.of("0", "0.05", "0.10", "0.20", "0.50"));
        var r = rows.getFirst();
        return new Config(
                number(r.get("version")),
                s.json.readValue(
                        r.get("bounds_json").toString(),
                        new tools.jackson.core.type.TypeReference<List<Integer>>() {}),
                s.json.readValue(
                        r.get("rates_json").toString(),
                        new tools.jackson.core.type.TypeReference<List<String>>() {}));
    }

    public Config get(CompanyAccess a) {
        s.require(a, "credit_limit:view");
        return read(a);
    }

    @Transactional
    public Map<String, Object> save(CompanyAccess a, String key, Config input) {
        s.require(a, "aging_config:edit");
        return s.execute(
                a,
                "aging_config.save",
                key,
                input,
                () -> {
                    validate(input);
                    s.jdbc.queryForList(
                            "SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",
                            a.tenantId(),
                            a.companyId());
                    var old =
                            s.jdbc.queryForList(
                                    "SELECT * FROM ar_aging_config WHERE tenant_id=? AND"
                                            + " company_id=? FOR UPDATE",
                                    a.tenantId(),
                                    a.companyId());
                    if (old.isEmpty()) {
                        if (input.version() == null || input.version() != 0)
                            throw invalid("账龄配置版本无效");
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " ar_aging_config(id,tenant_id,company_id,bounds_json,rates_json)"
                                    + " VALUES(?,?,?,?,?)",
                                Ids.next(),
                                a.tenantId(),
                                a.companyId(),
                                s.json.writeValueAsString(input.bounds()),
                                s.json.writeValueAsString(input.rates()));
                    } else {
                        version(input.version(), old.getFirst());
                        s.jdbc.update(
                                "UPDATE ar_aging_config SET"
                                        + " bounds_json=?,rates_json=?,version=version+1 WHERE"
                                        + " tenant_id=? AND company_id=?",
                                s.json.writeValueAsString(input.bounds()),
                                s.json.writeValueAsString(input.rates()),
                                a.tenantId(),
                                a.companyId());
                    }
                    s.audit.record(a, "aging_config.save", "company", a.companyId(), null, input);
                    return Map.of("config", read(a));
                });
    }

    static void validate(Config input) {
        if (input.bounds() == null
                || input.bounds().isEmpty()
                || input.bounds().size() > 10
                || input.rates() == null
                || input.rates().size() != input.bounds().size() + 1)
            throw invalid("账龄须有1至10个上限，每个区间都须配置比例");
        int last = -1;
        for (Integer bound : input.bounds()) {
            if (bound == null || bound <= last || bound > 36500)
                throw invalid("账龄天数上限须递增且不超过36500天");
            last = bound;
        }
        for (String rate : input.rates()) {
            if (rate == null || !rate.matches("[0-9]+(?:\\.[0-9]{1,6})?"))
                throw invalid("计提比例须为0至1之间的小数");
            var value = new BigDecimal(rate);
            if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)
                throw invalid("计提比例须为0至1之间的小数");
        }
    }
}
