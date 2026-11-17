package cn.erp.wms;

import static cn.erp.wms.PickTasks.*;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class Packing {
    private static final TypeReference<List<String>> SERIAL_LIST = new TypeReference<>() {};
    private static final String HEAD = """
            SELECT b.id,b.shipment_id,s.shipment_no,s.warehouse_id,b.box_no,b.weight_kg,b.volume,b.checker_id,
                   b.status,b.created_at,CONCAT('X:',s.shipment_no,'-',b.box_no) barcode
            FROM wms_pack b JOIN shipment s ON s.tenant_id=b.tenant_id AND s.company_id=b.company_id AND s.id=b.shipment_id
            WHERE b.tenant_id=? AND b.company_id=?
            """;
    private final JdbcTemplate jdbc;
    private final PickTasks picks;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ObjectMapper json;

    Packing(JdbcTemplate jdbc,PickTasks picks,BusinessCommands commands,AuditService audit,ObjectMapper json) {
        this.jdbc = jdbc;
        this.picks = picks;
        this.commands = commands;
        this.audit = audit;
        this.json = json;
    }

    record Create(Long shipmentId,List<Box> boxes) {}
    record Box(String boxNo,String weightKg,String volume,List<Line> lines) {}
    record Line(Long pickLineId,String qty,List<String> serialNos,String batchNo,String scanCode) {}
    private record CheckedBox(String number,BigDecimal weight,BigDecimal volume,List<CheckedLine> lines) {}
    private record CheckedLine(long pickLine,long sku,Long batch,BigDecimal quantity,List<String> serials) {}

    @Transactional(readOnly = true)
    Map<String,Object> page(CompanyAccess actor,Long shipment,Long warehouse,Long cursor,int limit) {
        checkPage(cursor,limit);
        var sql = new StringBuilder(HEAD);
        var params = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        scope(actor,sql,params,"s");
        filter(sql,params,"s.id",shipment);
        filter(sql,params,"s.warehouse_id",warehouse);
        if (cursor!=null) {
            sql.append(" AND b.id<?");
            params.add(cursor);
        }
        sql.append(" ORDER BY b.id DESC LIMIT ?");
        params.add(limit+1);
        return pageRows(jdbc.queryForList(sql.toString(),params.toArray()).stream().map(ApiRows::row).toList(),limit);
    }

    @Transactional(readOnly = true)
    Map<String,Object> detail(CompanyAccess actor,long pack) {
        var sql = new StringBuilder(HEAD).append(" AND b.id=?");
        var params = new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),pack));
        scope(actor,sql,params,"s");
        var row = jdbc.queryForList(sql.toString(),params.toArray()).stream().findFirst().orElseThrow(PickTasks::missing);
        var result = ApiRows.row(row);
        var lines = jdbc.queryForList("""
                SELECT l.id,l.pick_line_id,l.sku_id,l.batch_id,l.qty,l.serial_nos,s.code sku_code,s.name sku_name,
                    b.batch_no FROM wms_pack_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                    LEFT JOIN inv_batch b ON b.tenant_id=l.tenant_id AND b.company_id=l.company_id AND b.id=l.batch_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.pack_id=? ORDER BY l.id
                """,actor.tenantId(),actor.companyId(),pack);
        result.put("lines",lines.stream().map(line -> {
            var view = ApiRows.row(line);
            view.put("serialNos",json.readValue(line.get("serial_nos").toString(),SERIAL_LIST));
            return view;
        }).toList());
        return result;
    }

    @Transactional
    Map<String,Object> create(CompanyAccess actor,String key,Create input) {
        if (input.shipmentId()==null || input.shipmentId()<=0 || input.boxes()==null
                || input.boxes().isEmpty() || input.boxes().size()>100 || input.boxes().stream().anyMatch(b -> b==null))
            throw invalid("请选择发货单并录入 1 至 100 个箱");
        picks.head(actor,"t.source_id",input.shipmentId(),false);
        commands.execute(actor,"wms_pack.create",key,input,200,() -> {
            picks.lockShipment(actor,input.shipmentId());
            var task = picks.head(actor,"t.source_id",input.shipmentId(),true);
            if (!"done".equals(task.get("status")) || Boolean.TRUE.equals(task.get("short_pick")))
                throw conflict("请先完成拣货，短拣的发货单不可复核装箱");
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM wms_pack WHERE tenant_id=? AND company_id=? AND shipment_id=? AND status='done')
                    """,Boolean.class,actor.tenantId(),actor.companyId(),input.shipmentId())))
                throw conflict("该发货单已经完成复核装箱");
            var candidates = picks.lines(actor,value(task,"id"),true).stream()
                    .collect(Collectors.toMap(line -> value(line,"id"),Function.identity()));
            var boxes = checked(actor,input.boxes(),candidates);
            save(actor,input.shipmentId(),boxes);
            return Long.toString(input.shipmentId());
        },String.class);
        return page(actor,input.shipmentId(),null,null,100);
    }

    private List<CheckedBox> checked(CompanyAccess actor,List<Box> input,Map<Long,Map<String,Object>> candidates) {
        var numbers = new HashSet<String>();
        var sums = new HashMap<Long,BigDecimal>();
        var serialsByLine = new HashMap<Long,Set<String>>();
        var boxes = new ArrayList<CheckedBox>();
        for (var box : input) {
            String number = box.boxNo()==null ? "" : box.boxNo().strip().toUpperCase(Locale.ROOT);
            if (!number.matches("[A-Z0-9_-]{1,36}") || !numbers.add(number)) throw invalid("箱号无效或重复");
            if (box.lines()==null || box.lines().isEmpty() || box.lines().size()>500)
                throw invalid("每箱须录入 1 到 500 行复核明细");
            var boxLines = new ArrayList<CheckedLine>();
            var used = new HashSet<Long>();
            for (var line : box.lines()) {
                if (line==null || line.pickLineId()==null || !used.add(line.pickLineId()))
                    throw invalid("同一箱的拣货明细不可重复");
                var pick = candidates.get(line.pickLineId());
                var checked = checkLine(actor,line,pick,serialsByLine);
                sums.merge(checked.pickLine(),checked.quantity(),BigDecimal::add);
                boxLines.add(checked);
            }
            boxes.add(new CheckedBox(number,measure(box.weightKg()),measure(box.volume()),boxLines));
        }
        for (var candidate : candidates.entrySet()) {
            if (decimal(candidate.getValue(),"plan_qty").compareTo(sums.getOrDefault(candidate.getKey(),BigDecimal.ZERO))!=0)
                throw invalid("复核数量与拣货计划不一致：" + candidate.getValue().get("sku_code"));
        }
        return List.copyOf(boxes);
    }

    private CheckedLine checkLine(CompanyAccess actor,Line input,Map<String,Object> pick,Map<Long,Set<String>> scannedByLine) {
        if (pick==null || pick.get("allocation_id")==null) throw invalid("复核明细不属于该发货单或占用已失效");
        long id = input.pickLineId();
        var quantity = FinanceSupport.quantity(input.qty());
        var scanned = serials(input.serialNos());
        var expected = Set.copyOf(picks.selectionSerials(pick.get("scan_serials")));
        checkScan(actor,input,pick);
        if (expected.isEmpty() && !scanned.isEmpty()
                || !expected.isEmpty() && quantity.compareTo(BigDecimal.valueOf(scanned.size()))!=0)
            throw invalid("复核的序列号数量与明细数量不一致");
        var used = scannedByLine.computeIfAbsent(id,ignored -> new HashSet<>());
        for (var serial : scanned)
            if (!expected.contains(serial) || !used.add(serial)) throw invalid("序列号不在该拣货明细或已装入其他箱");
        return new CheckedLine(id,value(pick,"sku_id"),nullable(pick,"batch_id"),quantity,scanned);
    }

    private void checkScan(CompanyAccess actor,Line input,Map<String,Object> pick) {
        if (input.batchNo()!=null && !input.batchNo().isBlank()) checkBatch(input.batchNo(),pick);
        if (input.scanCode()==null || input.scanCode().isBlank()) return;
        String code = input.scanCode().strip();
        if (code.length()>160 || code.codePoints().anyMatch(Character::isISOControl)) throw invalid("复核扫码格式有误");
        if (code.regionMatches(true,0,"B:",0,2)) {
            checkBatch(code,pick);
            return;
        }
        if (code.equalsIgnoreCase(pick.get("sku_code").toString())) return;
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM sku_barcode WHERE tenant_id=? AND sku_id=? AND barcode=?)
                """,Boolean.class,actor.tenantId(),pick.get("sku_id"),code)))
            throw invalid("商品条码与复核明细不一致");
    }

    private BigDecimal measure(String value) {
        if (value==null || value.isBlank()) return null;
        var quantity = FinanceSupport.quantity(value);
        if (quantity.precision()>18) throw invalid("箱重或体积超出范围");
        return quantity;
    }

    private void save(CompanyAccess actor,long shipment,List<CheckedBox> boxes) {
        var headers = new ArrayList<Object[]>();
        var lines = new ArrayList<Object[]>();
        for (var box : boxes) {
            long id = Ids.next();
            headers.add(new Object[]{id,actor.tenantId(),actor.companyId(),shipment,box.number(),box.weight(),box.volume(),actor.userId()});
            for (var line : box.lines()) lines.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),id,line.pickLine(),
                    line.sku(),line.batch(),line.quantity(),json.writeValueAsString(line.serials())});
        }
        jdbc.batchUpdate("""
                INSERT INTO wms_pack(id,tenant_id,company_id,shipment_id,box_no,weight_kg,volume,checker_id)
                VALUES(?,?,?,?,?,?,?,?)
                """,headers);
        jdbc.batchUpdate("""
                INSERT INTO wms_pack_line(id,tenant_id,company_id,pack_id,pick_line_id,sku_id,batch_id,qty,serial_nos)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,lines);
        audit.record(actor,"wms_pack.create","shipment",shipment,null,Map.of("boxes",boxes.size()));
    }
}
