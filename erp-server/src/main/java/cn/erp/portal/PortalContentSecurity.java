package cn.erp.portal;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortalContentSecurity {
    private static final int MAX_WORDS=500;
    private static final int MAX_TEXT=16_000;
    private static final String WORDS="""
            SELECT id,word,enabled,version,created_at FROM portal_sensitive_word WHERE tenant_id=? AND company_id=?
            """;
    private final JdbcTemplate jdbc;

    PortalContentSecurity(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public record Filtered(String text,int hitCount) {}
    record Word(String id,String word,boolean enabled,int version,String createdAt) {}
    record Input(String word,Boolean enabled,Integer version) {}

    public Filtered filter(long tenant,long company,String text,String direction) {
        if(text==null || text.length()>MAX_TEXT || direction==null || !List.of("input","output").contains(direction))
            throw PortalValues.invalid("客服文本或过滤类型无效");
        String plain=normalized(text);
        if(plain.length()>2*MAX_TEXT) throw PortalValues.invalid("客服文本过长");
        var words=jdbc.queryForList("""
                SELECT word FROM portal_sensitive_word WHERE tenant_id=? AND company_id=? AND enabled=TRUE
                  ORDER BY CHAR_LENGTH(word) DESC,id
                """,String.class,tenant,company);
        if(words.isEmpty()) return new Filtered(plain,0);
        String expression=words.stream().map(Pattern::quote).collect(Collectors.joining("|"));
        var matcher=Pattern.compile(expression,Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(plain);
        var clean=new StringBuilder();
        int count=0;
        while(matcher.find()) {
            matcher.appendReplacement(clean,"[已过滤]");
            count++;
        }
        if(count==0) return new Filtered(plain,0);
        matcher.appendTail(clean);
        jdbc.update("INSERT INTO portal_content_hit(id,tenant_id,company_id,direction,hit_count) VALUES(?,?,?,?,?)",
                Ids.next(),tenant,company,direction,count);
        return new Filtered(clean.toString(),count);
    }

    PortalValues.Page<Word> list(CompanyAccess actor,Long cursor,int limit) {
        requireWholeCompany(actor);
        var rows=jdbc.query(WORDS+" AND id<? ORDER BY id DESC LIMIT ?",this::word,
                actor.tenantId(),actor.companyId(),PortalValues.cursor(cursor,limit),limit+1);
        return PortalValues.page(rows,limit,Word::id);
    }

    @Transactional
    public Word save(CompanyAccess actor,Long id,Input input) {
        requireWholeCompany(actor);
        String word=PortalValues.text(normalized(PortalValues.text(input.word(),100,"敏感词")),100,"敏感词");
        if(word.codePoints().anyMatch(Character::isISOControl)) throw PortalValues.invalid("敏感词只能填写单行内容");
        if(input.enabled()==null) throw PortalValues.invalid("请设置敏感词状态");
        long result;
        try {
            if(id==null) result=insert(actor,word,input.enabled());
            else {
                requireVersion(actor,id,input.version());
                jdbc.update("UPDATE portal_sensitive_word SET word=?,enabled=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                        word,input.enabled(),actor.tenantId(),actor.companyId(),id);
                result=id;
            }
        } catch(DuplicateKeyException conflict) {
            throw new ApiException(409,"PORTAL_WORD_EXISTS","当前公司已配置相同敏感词");
        }
        return read(actor,result,false);
    }

    @Transactional
    public Map<String,String> delete(CompanyAccess actor,long id,int version) {
        requireWholeCompany(actor);
        requireVersion(actor,id,version);
        jdbc.update("DELETE FROM portal_sensitive_word WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),id);
        return Map.of("id",Long.toString(id));
    }

    private long insert(CompanyAccess actor,String word,boolean enabled) {
        jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,actor.tenantId(),actor.companyId());
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM portal_sensitive_word WHERE tenant_id=? AND company_id=?",Long.class,
                actor.tenantId(),actor.companyId());
        if(count!=null && count>=MAX_WORDS) throw PortalValues.invalid("每家公司最多配置 500 个客服敏感词");
        long id=Ids.next();
        jdbc.update("INSERT INTO portal_sensitive_word(id,tenant_id,company_id,word,enabled,created_by) VALUES(?,?,?,?,?,?)",
                id,actor.tenantId(),actor.companyId(),word,enabled,actor.userId());
        return id;
    }

    private void requireVersion(CompanyAccess actor,long id,Integer version) {
        if(version==null || read(actor,id,true).version()!=version) throw new ApiException(409,"VERSION_CONFLICT","敏感词已变化，请刷新后重试");
    }

    private Word read(CompanyAccess actor,long id,boolean lock) {
        return jdbc.query(WORDS+" AND id=?"+(lock?" FOR UPDATE":""),this::word,actor.tenantId(),actor.companyId(),id)
                .stream().findFirst().orElseThrow(()->PortalValues.missing("敏感词"));
    }

    private Word word(java.sql.ResultSet row,int count) throws java.sql.SQLException {
        return new Word(row.getString("id"),row.getString("word"),row.getBoolean("enabled"),row.getInt("version"),row.getString("created_at"));
    }

    private String normalized(String text) {
        var result=new StringBuilder();
        Normalizer.normalize(text,Normalizer.Form.NFKC).codePoints()
                .filter(point->Character.getType(point)!=Character.FORMAT && point!=0x00AD)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private void requireWholeCompany(CompanyAccess actor) {
        if(!actor.allWarehouses() || !actor.orgScope().allDepartments())
            throw new ApiException(403,"FORBIDDEN","维护客服敏感词需要整家公司数据权限");
    }
}
