package cn.erp.gl;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import java.io.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import cn.erp.documents.*;
import cn.erp.exporting.*;
import cn.erp.reporting.ReportService;
import cn.erp.shared.*;

import static cn.erp.gl.GlTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlFinanceExportIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlVoucherService vouchers;
    @Autowired ExportJobs jobs;
    @Autowired ExportAccess access;
    @Autowired ReportService reports;
    @Autowired ObjectFiles files;
    @Autowired ObjectCompensation compensation;
    @Autowired ObjectMapper json;
    @Autowired FinancialExportPort financial;
    GlTestData d;
    ExportWorker worker;

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        long role = Ids.next();
        jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'FIN','财务导出角色')",
                role,
                d.tenant);
        jdbc.update(
                "INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                Ids.next(),
                d.tenant,
                d.maker,
                role);
        for (long permission : jdbc.queryForList("SELECT id FROM sys_permission", Long.class))
            jdbc.update(
                    "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)"
                            + " VALUES(?,?,?,?)",
                    Ids.next(),
                    d.tenant,
                    role,
                    permission);
        books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", d.october, null, 0L));
        var book =
                books.confirm(d.actor(d.reviewer), key(), new GlModels.Confirmation(0L, "财务样例核对"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(GlSupport.number(book.get("version")), null));
        var v =
                vouchers.save(
                        d.actor(d.maker),
                        null,
                        key(),
                        new GlModels.Voucher(
                                d.word(),
                                LocalDate.of(2026, 10, 15),
                                0,
                                List.of(
                                        new GlModels.Line(
                                                "月末费用",
                                                d.subject("6602"),
                                                "D",
                                                "10.25",
                                                "0",
                                                null,
                                                null,
                                                null),
                                        new GlModels.Line(
                                                "应付",
                                                d.subject("2241"),
                                                "C",
                                                "10.25",
                                                "0",
                                                null,
                                                null,
                                                null)),
                                null,
                                null));
        for (String action : List.of("submit", "approve", "post"))
            v =
                    vouchers.change(
                            d.actor(action.equals("approve") ? d.reviewer : d.maker),
                            GlSupport.number(v.get("id")),
                            action,
                            key(),
                            new GlModels.Version(GlSupport.number(v.get("version")), "核对"));
        worker = new ExportWorker(jobs, access, reports, files, json, compensation);
        ReflectionTestUtils.setField(worker, "financial", financial);
    }

    @AfterEach
    void cleanupFiles() {
        if (d != null)
            for (String key :
                    jdbc.queryForList(
                            "SELECT storage_key FROM attachment WHERE tenant_id=? AND"
                                    + " owner_type='export_job'",
                            String.class,
                            d.tenant)) files.delete(key);
    }

    long create(String type, String format) {
        var params = new LinkedHashMap<String, Object>();
        params.put("query", Map.of("periodFrom", d.october, "periodTo", d.october));
        params.put("fileType", "xlsx");
        if (format != null)
            params.put(
                    "formatId",
                    jdbc.queryForObject(
                            "SELECT id FROM gl_export_format WHERE tenant_id=? AND company_id=? AND"
                                    + " code=?",
                            Long.class,
                            d.tenant,
                            d.company,
                            format));
        return Long.parseLong(
                jobs.create(d.actor(d.maker), key(), new ExportJobs.Input(type, null, params))
                        .get("taskId")
                        .toString());
    }

    @Test
    void financialXlsxUsesQueueAndFrozenColumnMapping() throws Exception {
        long task = create("gl-vouchers", "generic_xlsx");
        jdbc.update(
                "UPDATE gl_export_format SET"
                    + " columns_json=JSON_ARRAY(JSON_OBJECT('field','digest','label','后来改的列标题'))"
                    + " WHERE tenant_id=? AND code='generic_xlsx'",
                d.tenant);
        assertThat(worker.runOne(d.tenant)).isTrue();
        assertThat(jobs.get(d.actor(d.maker), task)).containsEntry("status", "succeeded");
        var file = jobs.download(d.actor(d.maker), task);
        assertThat(file.contentType()).contains("spreadsheetml");
        String sheet = "";
        try (var zip =
                new ZipInputStream(
                        new ByteArrayInputStream(file.bytes()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null)
                if (e.getName().endsWith("sheet1.xml"))
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sheet).contains("科目编码", "月末费用", "10.25").doesNotContain("后来改的列标题");
    }

    @Test
    void revokedCostOrFinancialPermissionCannotDownloadOldFile() {
        long task = create("gl-vouchers", "generic_csv");
        worker.runOne(d.tenant);
        jdbc.update(
                "DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON"
                    + " p.id=rp.permission_id WHERE rp.tenant_id=? AND p.code='gl_export:create'",
                d.tenant);
        assertThatThrownBy(() -> jobs.download(d.actor(d.maker), task))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
    }

    @Test
    void revokedDepartmentScopeCannotReadTaskMetadata() {
        long task = create("gl-balance", null);
        var a =
                new cn.erp.identity.CompanyAccess(
                        d.tenant,
                        d.maker,
                        d.company,
                        true,
                        Set.of(),
                        d.permissions,
                        OrgScope.restricted(Set.of(), Set.of(), 0));
        assertThatThrownBy(() -> jobs.get(a, task))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(403));
    }
}
