package cn.erp.database;

import cn.erp.ErpApplication;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/constraints/fixtures.sql", config = @SqlConfig(encoding = "UTF-8"))
class DatabaseConstraintsIT {
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedWrites")
    void mysqlRejectsInvalidWrite(Scenario scenario) {
        String constraint = scenario.header().split(" ", 3)[1];
        assertThat(scenario.sql()).hasSize(1);
        assertConstraint(scenario.sql().getFirst(), constraint);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("acceptedWrites")
    void mysqlPersistsValidWrite(Scenario scenario) {
        List<String> statements = scenario.sql();
        for (String statement : statements.subList(0, statements.size() - 1)) {
            jdbc.update(statement);
        }
        assertThat(jdbc.queryForObject(statements.getLast(), Boolean.class)).isTrue();
        if (scenario.header().startsWith("P09 ")) {
            assertConstraint("UPDATE document_sequence SET next_value=next_value+1 WHERE id=73301",
                    "ck_document_sequence_value");
        }
    }

    private void assertConstraint(String statement, String constraint) {
        assertThatThrownBy(() -> jdbc.update(statement)).isInstanceOfSatisfying(DataAccessException.class,
                failure -> assertThat(failure.getMostSpecificCause().getMessage()).contains(constraint));
    }

    static List<Scenario> rejectedWrites() throws IOException {
        return load("rejected.sql", 41);
    }

    static List<Scenario> acceptedWrites() throws IOException {
        return load("accepted.sql", 13);
    }

    private static List<Scenario> load(String file, int expectedCount) throws IOException {
        String text = new ClassPathResource("constraints/" + file).getContentAsString(StandardCharsets.UTF_8);
        List<Scenario> cases = new ArrayList<>();
        for (String block : text.replace("\r\n", "\n").strip().split("\n\\s*\n")) {
            List<String> lines = block.lines().toList();
            if (lines.size() < 2) {
                continue;
            }
            String header = lines.getFirst();
            assertThat(header).startsWith("-- ");
            String sql = String.join("\n", lines.subList(1, lines.size()));
            List<String> statements = List.of(sql.split(";")).stream().map(String::strip)
                    .filter(value -> !value.isEmpty()).toList();
            cases.add(new Scenario(header.substring(3), statements));
        }
        assertThat(cases).hasSize(expectedCount);
        return cases;
    }

    record Scenario(String header, List<String> sql) {
        @Override
        public String toString() {
            return header;
        }
    }
}
