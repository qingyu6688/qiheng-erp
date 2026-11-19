package cn.erp.customfield;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class CustomFields {
    private static final String[] INDEXES = {"cf_idx_1", "cf_idx_2", "cf_idx_3", "cf_idx_4", "cf_idx_5"};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    CustomFields(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    public record Field(String id, String fieldKey, String label, String type, List<String> choices,
                        String dictionaryCode, JsonNode defaultValue, boolean required, Integer indexSlot,
                        boolean listVisible, boolean printVisible, boolean importExport, int sortNo,
                        boolean enabled, long version) {
        public boolean searchable() { return indexSlot != null; }
    }
    record Input(String fieldKey, String label, String type, List<String> choices, String dictionaryCode,
                 JsonNode defaultValue, boolean required, boolean searchable, boolean listVisible,
                 boolean printVisible, boolean importExport, int sortNo, boolean enabled, Long version) {}

    public List<Field> fields(long tenant, String entity) { return fields(tenant, entity, false); }

    List<Field> fields(long tenant, String entity, boolean all) {
        entity(entity);
        return jdbc.query("SELECT * FROM cf_definition WHERE tenant_id=? AND entity=?" + (all ? "" : " AND enabled=1")
                        + " ORDER BY sort_no,id", (row, index) -> {
            String dictionary = row.getString("dictionary_code");
            String defaultValue = row.getString("default_value");
            return new Field(row.getString("id"), row.getString("field_key"), row.getString("label"), row.getString("field_type"),
                    dictionary == null ? json.readValue(row.getString("choices"), STRING_LIST) : choices(tenant, dictionary),
                    dictionary, defaultValue == null ? null : json.readTree(defaultValue), row.getBoolean("required"),
                    row.getObject("index_slot",Integer.class), row.getBoolean("list_visible"), row.getBoolean("print_visible"),
                    row.getBoolean("import_export"), row.getInt("sort_no"), row.getBoolean("enabled"), row.getLong("version"));
        }, tenant, entity);
    }

    @Transactional
    Field save(ActorScope actor, String entity, Input input) {
        entity(entity);
        if (input == null || input.fieldKey() == null || !input.fieldKey().matches("cf_[a-z][a-z0-9_]{0,31}")
                || input.label() == null || input.label().isBlank() || input.label().length() > 80
                || input.type() == null || !CustomFieldTypes.TYPES.contains(input.type())
                || input.sortNo() < 0 || input.sortNo() > 10000)
            throw CustomFieldTypes.invalid("字段编码、标题、类型或排序无效");
        jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, actor.tenantId());
        var known = fields(actor.tenantId(), entity, true);
        var old = known.stream().filter(f -> f.fieldKey().equals(input.fieldKey())).findFirst().orElse(null);
        if (old == null && known.size() >= 30) throw CustomFieldTypes.invalid("每种单据或主数据最多配置 30 个字段");
        if (old != null && !old.type().equals(input.type())) throw CustomFieldTypes.invalid("已有字段类型不能直接变更");
        if (old == null ? input.version() != null : input.version() == null || old.version() != input.version())
            throw new ApiException(409, "VERSION_CONFLICT", "字段配置已变化，请刷新后继续");
        List<String> choices = validateChoices(actor.tenantId(), input);
        Object defaultValue = CustomFieldTypes.value(input.type(), input.defaultValue(), choices, input.label());
        if (defaultValue != null && Set.of("user","department").contains(input.type()))
            throw CustomFieldTypes.invalid("人员和部门随公司授权变化，不支持企业级默认值");
        ensureReference(actor, input.type(), defaultValue, input.label());
        Integer slot = input.searchable() && input.enabled() ? slot(known, old, input.type()) : null;
        if (slot != null && defaultValue != null) indexValue(input.fieldKey(), defaultValue, input.label());
        long id = old == null ? Ids.next() : Long.parseLong(old.id());
        var parameters = new ArrayList<>(Arrays.asList(id, actor.tenantId(), entity, input.fieldKey(), input.label().strip(),
                input.type(), json.writeValueAsString(choices), valueOrNull(input.dictionaryCode()),
                defaultValue == null ? null : json.writeValueAsString(defaultValue), input.required(), slot, input.listVisible(),
                input.printVisible(), input.importExport(), input.sortNo(), input.enabled()));
        jdbc.update("""
                INSERT INTO cf_definition(id,tenant_id,entity,field_key,label,field_type,choices,dictionary_code,default_value,
                  required,index_slot,list_visible,print_visible,import_export,sort_no,enabled)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS staged
                ON DUPLICATE KEY UPDATE label=staged.label,choices=staged.choices,dictionary_code=staged.dictionary_code,
                  default_value=staged.default_value,required=staged.required,index_slot=staged.index_slot,list_visible=staged.list_visible,
                  print_visible=staged.print_visible,import_export=staged.import_export,sort_no=staged.sort_no,enabled=staged.enabled,version=cf_definition.version+1
                """, parameters.toArray());
        if (slot != null && (old == null || !Objects.equals(old.indexSlot(), slot))) reindex(actor.tenantId(), entity, input, slot);
        var maintained = fields(actor.tenantId(), entity, true).stream().filter(f -> f.fieldKey().equals(input.fieldKey())).findFirst().orElseThrow();
        audit.record(actor, "custom_field.save", "cf_definition", id, old, maintained);
        return maintained;
    }

    public void write(CompanyAccess actor, String entity, long id, JsonNode input, boolean created) {
        write(actor, entity, id, input, created, fields(actor.tenantId(), entity));
    }

    /** 入口沿用外部单据和主数据事务及权限，字段修改不触发额外业务动作。 */
    public void write(CompanyAccess actor, String entity, long id, JsonNode input, boolean created, List<Field> fields) {
        if (fields.isEmpty()) return;
        if (input != null && !input.isNull() && !input.isObject()) throw CustomFieldTypes.invalid("自定义字段必须是对象");
        entity(entity);
        var filters = new ArrayList<Object>(List.of(actor.tenantId(), id));
        String where = "tenant_id=? AND id=?";
        if (!Set.of("sku", "business_partner").contains(entity)) {
            where += " AND company_id=?";
            filters.add(actor.companyId());
        }
        var row = created ? json.createObjectNode() : json.readTree(jdbc.queryForObject("SELECT ext_json FROM " + entity + " WHERE " + where,
                String.class, filters.toArray())).deepCopy();
        var stored = (tools.jackson.databind.node.ObjectNode) row;
        var index = new String[INDEXES.length];
        for (var field : fields) {
            boolean sent = input != null && input.has(field.fieldKey());
            boolean existing = stored.has(field.fieldKey());
            JsonNode selected = sent ? input.get(field.fieldKey()) : existing ? stored.get(field.fieldKey()) : field.defaultValue();
            Object normalized = !sent && existing ? existingValue(selected) : CustomFieldTypes.value(field.type(), selected, field.choices(), field.label());
            if (empty(normalized) && field.required()) throw CustomFieldTypes.invalid("请填写" + field.label());
            if (sent || !existing) ensureReference(actor, field.type(), normalized, field.label());
            if (normalized == null) stored.remove(field.fieldKey());
            else stored.set(field.fieldKey(),json.valueToTree(normalized));
            if (field.indexSlot() != null && normalized != null)
                index[field.indexSlot()-1] = indexValue(field.fieldKey(),normalized,field.label());
        }
        var bind = new ArrayList<Object>();
        bind.add(json.writeValueAsString(stored));
        Collections.addAll(bind,index);
        bind.addAll(filters);
        jdbc.update("UPDATE " + entity + " SET ext_json=?,cf_idx_1=?,cf_idx_2=?,cf_idx_3=?,cf_idx_4=?,cf_idx_5=? WHERE " + where, bind.toArray());
    }

    public Map<String,Object> visible(String raw, List<Field> fields) {
        var result = new LinkedHashMap<String,Object>();
        if (raw != null && !fields.isEmpty()) {
            var parsed = json.readTree(raw);
            for (var field : fields) {
                JsonNode value = parsed.get(field.fieldKey());
                if (value != null && !value.isNull()) result.put(field.fieldKey(), existingValue(value));
            }
        }
        return result;
    }

    public List<String> importKeys(long tenant, String type) {
        String entity = switch (type) { case "sku" -> "sku"; case "partner" -> "business_partner"; default -> null; };
        return entity == null ? List.of() : fields(tenant,entity).stream().filter(Field::importExport).map(Field::fieldKey).toList();
    }

    public JsonNode importValues(ActorScope actor,String type,List<String> keys,List<String> cells) {
        if(keys.isEmpty()) return null;
        String entity = type.equals("sku") ? "sku" : "business_partner";
        var definitions = fields(actor.tenantId(),entity);
        var result = json.createObjectNode();
        for (int i=0; i<keys.size(); i++) {
            String key=keys.get(i);
            var definition=definitions.stream().filter(f -> f.fieldKey().equals(key)&&f.importExport()).findFirst()
                    .orElseThrow(() -> CustomFieldTypes.invalid("字段已停用或不允许导入：" + key));
            String value=cells.get(i).strip();
            if (value.isEmpty()) continue;
            if (definition.type().equals("multi_select")) result.set(key,json.valueToTree(Arrays.asList(value.split("\\|",-1))));
            else result.put(key,value);
        }
        return result;
    }

    public String predicates(long tenant, String entity, String alias, Map<String,String[]> parameters, List<Object> bind) {
        if (!alias.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("自定义字段查询别名无效");
        var names = parameters.keySet().stream().filter(k -> k.startsWith("cf.")).sorted().toList();
        if (names.isEmpty()) return "";
        if (names.size() > INDEXES.length) throw CustomFieldTypes.invalid("可同时筛选的自定义字段过多");
        var fields = fields(tenant, entity);
        var sql = new StringBuilder();
        for (String name : names) {
            var field = fields.stream().filter(f -> f.fieldKey().equals(name.substring(3)) && f.indexSlot()!=null)
                    .findFirst().orElseThrow(() -> CustomFieldTypes.invalid("存在未启用搜索的自定义字段"));
            String[] values = parameters.get(name);
            if (values == null || values.length != 1 || values[0].isBlank()) throw CustomFieldTypes.bad(field.label());
            Object value = CustomFieldTypes.value(field.type(),json.getNodeFactory().stringNode(values[0]),field.choices(),field.label());
            sql.append(" AND ").append(alias).append('.').append(INDEXES[field.indexSlot()-1]).append("=?");
            bind.add(indexValue(field.fieldKey(), value, field.label()));
        }
        return sql.toString();
    }

    private void reindex(long tenant, String entity, Input input, int slot) {
        String table = CustomFieldTypes.VIEW.keySet().stream().filter(entity::equals)
                .findFirst().orElseThrow(() -> CustomFieldTypes.invalid("自定义字段业务类型无效"));
        String path = "$." + input.fieldKey();
        String predicate = "tenant_id=? AND JSON_EXTRACT(ext_json,?) IS NOT NULL";
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + predicate
                + " AND CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(ext_json,?)))>?)", Boolean.class, tenant, path, path, 127-input.fieldKey().length())))
            throw CustomFieldTypes.invalid(input.label() + "的部分历史值过长，无法建立索引");
        jdbc.update("UPDATE " + table + " SET " + INDEXES[slot-1] + "=CONCAT(?,JSON_UNQUOTE(JSON_EXTRACT(ext_json,?))) WHERE " + predicate,
                input.fieldKey()+"=", path, tenant, path);
    }

    private List<String> validateChoices(long tenant, Input input) {
        if (!Set.of("select", "multi_select").contains(input.type())) return List.of();
        String dictionary = valueOrNull(input.dictionaryCode());
        List<String> items = dictionary == null ? input.choices() : choices(tenant, dictionary);
        if (items == null || items.isEmpty() || items.size() > 100 || items.stream().anyMatch(v -> v == null || v.isBlank() || v.length() > 100 || !v.equals(v.strip())))
            throw CustomFieldTypes.invalid("请输入有效且不重复的选项，或选择已启用的字典");
        if (new HashSet<>(items).size() != items.size()) throw CustomFieldTypes.invalid("自定义字段选项不能重复");
        return List.copyOf(items);
    }

    private List<String> choices(long tenant, String code) {
        return jdbc.queryForList("""
                SELECT DISTINCT i.value FROM sys_dict_item i LEFT JOIN sys_dict_item_override o ON o.tenant_id=? AND o.item_id=i.id
                WHERE i.tenant_id IN (0,?) AND i.type_code=? AND i.status='active' AND COALESCE(o.disabled,0)=0
                  AND EXISTS(SELECT 1 FROM sys_dict_type t WHERE t.tenant_id=i.tenant_id AND t.code=i.type_code AND t.status='active')
                ORDER BY i.value LIMIT 101
                """, String.class, tenant, tenant, code);
    }

    private Integer slot(List<Field> known, Field old, String type) {
        if (Set.of("textarea", "multi_select").contains(type)) throw CustomFieldTypes.invalid("多行文本或多选字段不支持精确筛选");
        if (old != null && old.indexSlot() != null) return old.indexSlot();
        for (int i=1; i<=INDEXES.length; i++) {
            int selected = i;
            if (known.stream().noneMatch(f -> Objects.equals(f.indexSlot(), selected))) return i;
        }
        throw CustomFieldTypes.invalid("每种单据或主数据最多启用 5 个搜索字段");
    }

    private void ensureReference(ActorScope actor, String type, Object value, String label) {
        if (value == null) return;
        if (type.equals("user") && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND u.status='active'
                  AND u.user_type='human' AND (?=0 OR EXISTS(SELECT 1 FROM org_user_data_scope s
                    WHERE s.tenant_id=u.tenant_id AND s.user_id=u.id AND s.company_id=?)))
                """, Boolean.class, actor.tenantId(), value, actor.companyId(), actor.companyId()))) throw CustomFieldTypes.bad(label);
        if (type.equals("department") && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM org_department WHERE tenant_id=? AND id=? AND (?=0 OR company_id=?) AND status='active')
                """, Boolean.class, actor.tenantId(), value, actor.companyId(), actor.companyId()))) throw CustomFieldTypes.bad(label);
    }

    private String indexValue(String key, Object value, String label) {
        String indexed = key+"="+value;
        if (value == null || indexed.length() > 128) throw CustomFieldTypes.invalid(label + "的筛选值过长或无效");
        return indexed;
    }

    private Object existingValue(JsonNode value) { return value.isNull() ? null : json.convertValue(value, Object.class); }
    private boolean empty(Object value) { return value == null || value instanceof Collection<?> values && values.isEmpty(); }
    private static String valueOrNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    static void entity(String entity) {
        if (entity == null || !CustomFieldTypes.VIEW.containsKey(entity)) throw CustomFieldTypes.invalid("自定义字段业务类型无效");
    }
}
