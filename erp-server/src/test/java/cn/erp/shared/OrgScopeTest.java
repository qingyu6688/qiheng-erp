package cn.erp.shared;

import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OrgScopeTest {
    @Test void intersectionPreservesMixedDepartmentAndSelfConditions() {
        var first=OrgScope.restricted(Set.of(1L),Set.of(5L),1);
        var second=OrgScope.restricted(Set.of(2L),Set.of(6L),2);
        var result=first.intersect(second);
        assertThat(result.allows(1,6)).isTrue();assertThat(result.allows(2,5)).isTrue();
        assertThat(result.allows(1,5)).isFalse();assertThat(result.allows(3,5)).isFalse();
        assertThat(first.covers(result)).isTrue();assertThat(result.covers(first)).isFalse();
    }
    @Test void legacyAndEmptyScopesDoNotGrantAccess() {
        var empty=OrgScope.restricted(Set.of(),Set.of(),0);
        assertThat(empty.allows(1,1)).isFalse();assertThat(empty.covers(empty)).isTrue();
        assertThat(empty.covers(OrgScope.all())).isFalse();assertThat(OrgScope.all().allows(0,0)).isTrue();
        assertThat(OrgScope.restricted(Set.of(),Set.of(1L),1).allows(0,1)).isFalse();
    }
}
