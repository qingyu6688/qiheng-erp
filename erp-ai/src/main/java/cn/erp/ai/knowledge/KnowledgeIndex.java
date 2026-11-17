package cn.erp.ai.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.erp.ai.knowledge.KnowledgeService.Evidence;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
final class KnowledgeIndex {
    static final int CANDIDATES = 30;
    private static final int VECTOR_SIZE = 1024;
    private static final int BATCH = 16;
    private static final int RRF_OFFSET = 60;
    private static final Pattern INDEX_ID = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final Pattern QUERY_WORDS = Pattern.compile("[\\p{IsHan}]+|[a-z0-9_]+");
    private static final Pattern COMMON_TERMS = Pattern.compile("^(如何|怎么|什么|哪些|可以|能否|是否|在哪|哪个|进行|怎样|请问|系统|一下)$");
    private final ObjectMapper json;
    private final KnowledgeHttp http;
    private final boolean local;
    private final String searchUrl;
    private final String searchAuth;
    private final String analyzer;
    private final String embeddingUrl;
    private final String embeddingAuth;
    private final String embeddingModel;
    private final String rerankUrl;
    private final String rerankAuth;
    private final String rerankModel;
    private final double minScore;
    private final Set<String> knownIndexes = ConcurrentHashMap.newKeySet();

    KnowledgeIndex(ObjectMapper json, KnowledgeHttp http,
                   @Value("${erp.ai.search-mode:${ERP_AI_SEARCH_MODE:opensearch}}") String mode,
                   @Value("${erp.ai.search-url:${ERP_AI_SEARCH_URL:}}") String searchUrl,
                   @Value("${erp.ai.search-username:${ERP_AI_SEARCH_USERNAME:}}") String username,
                   @Value("${erp.ai.search-password:${ERP_AI_SEARCH_PASSWORD:}}") String password,
                   @Value("${erp.ai.search-analyzer:${ERP_AI_SEARCH_ANALYZER:cjk}}") String analyzer,
                   @Value("${erp.ai.embedding-url:${ERP_AI_EMBEDDING_URL:}}") String embeddingUrl,
                   @Value("${erp.ai.embedding-token:${ERP_AI_EMBEDDING_TOKEN:}}") String embeddingToken,
                   @Value("${erp.ai.embedding-model:${ERP_AI_EMBEDDING_MODEL:BAAI/bge-m3}}") String embeddingModel,
                   @Value("${erp.ai.rerank-url:${ERP_AI_RERANK_URL:}}") String rerankUrl,
                   @Value("${erp.ai.rerank-token:${ERP_AI_RERANK_TOKEN:}}") String rerankToken,
                   @Value("${erp.ai.rerank-model:${ERP_AI_RERANK_MODEL:BAAI/bge-reranker-v2-m3}}") String rerankModel,
                   @Value("${erp.ai.rerank-min-score:${ERP_AI_RERANK_MIN_SCORE:0.15}}") double minScore) {
        if (!Set.of("local", "opensearch").contains(mode) || !Set.of("cjk", "icu_analyzer").contains(analyzer)
                || !Double.isFinite(minScore)) {
            throw new IllegalArgumentException("知识检索模式、中文分词配置或重排阈值无效");
        }
        this.json = json;
        this.http = http;
        this.local = "local".equals(mode);
        this.searchUrl = searchUrl.replaceAll("/+$", "");
        this.searchAuth = username.isBlank() ? "" : "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.analyzer = analyzer;
        this.embeddingUrl = embeddingUrl;
        this.embeddingAuth = KnowledgeHttp.bearer(embeddingToken);
        this.embeddingModel = embeddingModel;
        this.rerankUrl = rerankUrl;
        this.rerankAuth = KnowledgeHttp.bearer(rerankToken);
        this.rerankModel = rerankModel;
        this.minScore = minScore;
    }

    boolean local() {
        return local;
    }

    void index(String tenant, boolean wiki, List<Entry> items) {
        if (local || items.isEmpty()) {
            return;
        }
        String name = indexName(tenant, wiki);
        ensureIndex(name);
        for (int from = 0; from < items.size(); from += BATCH) {
            var batch = items.subList(from, Math.min(items.size(), from + BATCH));
            var vectors = embed(batch.stream().map(item -> item.title() + "\n" + item.heading() + "\n" + truncate(item.content(), 4000)).toList());
            var body = new StringBuilder();
            for (int i = 0; i < batch.size(); i++) {
                Entry item = batch.get(i);
                body.append(json.writeValueAsString(Map.of("index", Map.of("_id", item.id())))).append('\n');
                body.append(json.writeValueAsString(Map.of(
                        "library_id", item.libraryIds(), "library_count", item.libraryIds().size(),
                        "document_id", item.documentId(), "title", item.title(), "heading_path", item.heading(),
                        "content", item.content(), "embedding", vectors.get(i), "acl_roles", item.roles(),
                        "acl_departments", item.departments()))).append('\n');
            }
            JsonNode result = checked(http.send(searchUrl + "/" + name + "/_bulk?refresh=wait_for", "POST", searchAuth,
                    new KnowledgeHttp.Ndjson(body.toString()), 30));
            if (result.path("errors").asBoolean(true)) {
                throw KnowledgeHttp.unavailable();
            }
        }
    }

    void delete(String tenant, boolean wiki, String id) {
        if (local) {
            return;
        }
        var response = http.send(searchUrl + "/" + indexName(tenant, wiki) + "/_doc/" + id,
                "DELETE", searchAuth, null, 15);
        if (!response.ok() && response.status() != 404) {
            throw KnowledgeHttp.unavailable();
        }
    }

    List<Hit> recall(String tenant, String question, Set<String> libraries) {
        if (libraries.isEmpty()) {
            return List.of();
        }
        requireConfigured();
        List<Double> vector = embed(List.of(question)).getFirst();
        List<Hit> hits = new ArrayList<>();
        for (boolean wiki : List.of(true, false)) {
            String name = indexName(tenant, wiki);
            Object filter = wiki ? Map.of("terms_set", Map.of("library_id", Map.of(
                    "terms", libraries, "minimum_should_match_field", "library_count"))) : Map.of("terms", Map.of("library_id", libraries));
            var lexical = Map.of("bool", Map.of("filter", List.of(filter), "must",
                    List.of(Map.of("multi_match", Map.of("query", question, "fields", List.of("title^4", "heading_path^2", "content"))))));
            var semantic = Map.of("knn", Map.of("embedding", Map.of("vector", vector, "k", CANDIDATES, "filter", filter)));
            Map<String, Double> scores = new HashMap<>();
            fuse(scores, ids(query(name, lexical)));
            fuse(scores, ids(query(name, semantic)));
            scores.forEach((id, score) -> hits.add(new Hit(wiki ? "wiki:" + id : id, score)));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id)).toList();
    }

    List<Ranked> rerank(String question, List<Ranked> candidates) {
        if (local || candidates.isEmpty()) {
            return candidates;
        }
        // 外部检索结果只提供 ID。这里的正文已由 MySQL 校验租户、库范围、版本和全部来源。
        var docs = candidates.stream().map(candidate -> candidate.evidence().title() + "\n"
                + candidate.evidence().heading() + "\n" + truncate(candidate.evidence().content(), 3000)).toList();
        var response = checked(http.send(rerankUrl, "POST", rerankAuth, Map.of("model", rerankModel, "query", question,
                "documents", docs, "top_n", docs.size()), 20));
        Map<Integer, Double> scores = new HashMap<>();
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            double score = item.path("relevance_score").asDouble(Double.NaN);
            if (index < 0 || index >= docs.size() || !Double.isFinite(score) || scores.put(index, score) != null) {
                throw KnowledgeHttp.unavailable();
            }
        }
        if (scores.size() != docs.size()) {
            throw KnowledgeHttp.unavailable();
        }
        return IntStream.range(0, docs.size()).mapToObj(i -> new Ranked(candidates.get(i).evidence(), scores.get(i),
                scores.get(i) >= minScore)).toList();
    }

    private void ensureIndex(String name) {
        requireConfigured();
        if (knownIndexes.contains(name)) {
            return;
        }
        var response = http.send(searchUrl + "/" + name, "HEAD", searchAuth, null, 10);
        if (response.status() == 404) {
            var text = Map.of("type", "text", "analyzer", analyzer);
            var keyword = Map.of("type", "keyword");
            var vector = Map.of("type", "knn_vector", "dimension", VECTOR_SIZE,
                    "method", Map.of("name", "hnsw", "engine", "lucene", "space_type", "cosinesimil"));
            response = http.send(searchUrl + "/" + name, "PUT", searchAuth, Map.of(
                    "settings", Map.of("index", Map.of("knn", true, "number_of_shards", 1, "number_of_replicas", 0)),
                    "mappings", Map.of("properties", Map.of("library_id", keyword, "library_count", Map.of("type", "integer"),
                            "document_id", keyword, "title", text, "heading_path", text, "content", text,
                            "embedding", vector, "acl_roles", keyword, "acl_departments", keyword))), 20);
            if (!response.ok() && (response.data() == null
                    || !"resource_already_exists_exception".equals(response.data().path("error").path("type").asString()))) {
                throw KnowledgeHttp.unavailable();
            }
        } else if (!response.ok()) {
            throw KnowledgeHttp.unavailable();
        }
        knownIndexes.add(name);
    }

    private JsonNode query(String name, Object query) {
        var response = http.send(searchUrl + "/" + name + "/_search", "POST", searchAuth,
                Map.of("size", CANDIDATES, "_source", false, "query", query), 10);
        if (response.status() == 404 && response.data() != null
                && "index_not_found_exception".equals(response.data().path("error").path("type").asString())) {
            return json.createObjectNode();
        }
        return checked(response);
    }

    private List<List<Double>> embed(List<String> input) {
        var response = checked(http.send(embeddingUrl, "POST", embeddingAuth,
                Map.of("model", embeddingModel, "input", input), 20));
        Map<Integer, List<Double>> vectors = new HashMap<>();
        for (JsonNode item : response.path("data")) {
            int index = item.path("index").asInt(-1);
            List<Double> vector = new ArrayList<>();
            for (JsonNode number : item.path("embedding")) {
                double value = number.asDouble(Double.NaN);
                if (!Double.isFinite(value)) {
                    throw KnowledgeHttp.unavailable();
                }
                vector.add(value);
            }
            if (vector.size() != VECTOR_SIZE || vectors.put(index, vector) != null) {
                throw KnowledgeHttp.unavailable();
            }
        }
        List<List<Double>> result = IntStream.range(0, input.size()).mapToObj(vectors::get).toList();
        if (vectors.size() != input.size() || result.stream().anyMatch(item -> item == null)) {
            throw KnowledgeHttp.unavailable();
        }
        return result;
    }

    private List<String> ids(JsonNode response) {
        var ids = new ArrayList<String>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            String id = hit.path("_id").asString();
            if (INDEX_ID.matcher(id).matches()) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void fuse(Map<String, Double> scores, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1d / (RRF_OFFSET + i + 1), Double::sum);
        }
    }

    static double lexical(String question, String context) {
        Set<String> terms = new HashSet<>();
        var matcher = QUERY_WORDS.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN && word.length() > 1) {
                for (int i = 0; i < word.length() - 1; i++) {
                    String term = word.substring(i, i + 2);
                    if (!COMMON_TERMS.matcher(term).matches()) {
                        terms.add(term);
                    }
                }
            } else if (word.length() > 1) {
                terms.add(word);
            }
        }
        String text = context.toLowerCase(Locale.ROOT);
        return terms.stream().filter(text::contains).count() / (double) Math.max(terms.size(), 1);
    }

    private static String truncate(String text, int limit) {
        return text.codePointCount(0, text.length()) <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private String indexName(String tenant, boolean wiki) {
        String key = INDEX_ID.matcher(tenant).matches() ? tenant : KnowledgeParser.digest(tenant.getBytes(StandardCharsets.UTF_8));
        return (wiki ? "erp-wiki-" : "erp-kb-") + key;
    }

    private void requireConfigured() {
        if (searchUrl.isBlank() || embeddingUrl.isBlank() || rerankUrl.isBlank()) {
            throw KnowledgeHttp.unavailable();
        }
    }

    private JsonNode checked(KnowledgeHttp.Response response) {
        if (!response.ok() || response.data() == null) {
            throw KnowledgeHttp.unavailable();
        }
        return response.data();
    }

    record Entry(String id, String documentId, String title, String heading, String content,
                 Set<String> libraryIds, Set<String> roles, Set<String> departments) {}
    record Hit(String id, double score) {}
    record Ranked(Evidence evidence, double score, boolean relevant) {}
}
