package cn.erp.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class KnowledgeIndexTest {
    @Test
    void usesTenantIndexesLibraryIntersectionAndReciprocalRankFusion() {
        var mapper = JsonMapper.builder().build();
        var http = mock(KnowledgeHttp.class);
        var requests = new ArrayList<String>();
        when(http.send(eq("http://embed/v1/embeddings"), eq("POST"), eq(""), any(), anyInt()))
                .thenReturn(new KnowledgeHttp.Response(200, mapper.valueToTree(Map.of("data",
                        List.of(Map.of("index", 0, "embedding", java.util.Collections.nCopies(1024, 0.1)))))));
        for (String type : List.of("wiki", "kb")) {
            String url = "http://search/erp-" + type + "-301/_search";
            when(http.send(eq(url), eq("POST"), eq(""), any(), anyInt())).thenAnswer(invocation -> {
                String body = mapper.writeValueAsString(invocation.getArgument(3));
                requests.add(url + " " + body);
                boolean vector = body.contains("\"knn\"");
                String id = type + "-shared";
                return new KnowledgeHttp.Response(200, mapper.valueToTree(Map.of("hits", Map.of("hits",
                        List.of(Map.of("_id", id), Map.of("_id", vector ? type + "-vector" : type + "-text"))))));
            });
        }
        var index = new KnowledgeIndex(mapper, http, "opensearch", "http://search", "", "", "cjk",
                "http://embed/v1/embeddings", "", "m", "http://rerank/v1/rerank", "", "r", 0.15);
        var hits = index.recall("301", "采购转单", Set.of("lib-a", "lib-b"));
        assertThat(hits.stream().limit(2)).extracting(KnowledgeIndex.Hit::id).containsExactly("kb-shared", "wiki:wiki-shared");
        assertThat(requests).hasSize(4).allMatch(body -> body.contains("lib-a") && body.contains("lib-b"));
        assertThat(requests.stream().filter(body -> body.contains("erp-wiki"))).allMatch(body ->
                body.contains("minimum_should_match_field") && body.contains("library_count"));
        assertThat(requests.stream().filter(body -> body.contains("erp-kb"))).allMatch(body ->
                body.contains("\"terms\"") && body.contains("\"library_id\""));
    }
}
