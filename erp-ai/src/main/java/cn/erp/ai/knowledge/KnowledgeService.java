package cn.erp.ai.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.knowledge.KnowledgeIndex.Ranked;
import cn.erp.ai.knowledge.KnowledgeRepository.Source;
import cn.erp.ai.knowledge.KnowledgeTypes.Recall;

@Service
public class KnowledgeService {
    private static final int SELECTED = 8;
    private final KnowledgeRepository repository;
    private final WikiService wiki;
    private final KnowledgeIndex index;

    KnowledgeService(KnowledgeRepository repository, WikiService wiki, KnowledgeIndex index) {
        this.repository = repository;
        this.wiki = wiki;
        this.index = index;
    }

    public List<Evidence> search(AiActor actor, String question, Set<String> libraryIds) {
        return diagnostics(actor, question, libraryIds).stream().filter(Recall::selected).map(Recall::evidence).toList();
    }

    List<Recall> diagnostics(AiActor actor, String query, Set<String> libraryIds) {
        String question = KnowledgeCatalog.required(query, 4000, "请输入有效的检索问题");
        if (libraryIds != null && libraryIds.size() > 200) {
            throw KnowledgeRepository.invalid("单次最多可指定 200 个知识库");
        }
        Set<String> libraries = repository.allowed(actor, libraryIds);
        if (libraries.isEmpty()) {
            return List.of();
        }
        List<Ranked> result = index.local() ? local(actor, question, libraries) : remote(actor, question, libraries);
        Comparator<Ranked> byPriority = Comparator.comparing((Ranked item) -> item.evidence().wikiSlug() == null)
                .thenComparing(Comparator.comparingDouble(Ranked::score).reversed())
                .thenComparing(item -> item.evidence().segmentId());
        List<Recall> diagnostics = new ArrayList<>();
        int selected = 0;
        for (Ranked item : result.stream().sorted(byPriority).toList()) {
            Evidence current = find(actor, item.evidence().segmentId(), libraries);
            if (current == null || !current.fingerprint().equals(item.evidence().fingerprint())) {
                continue;
            }
            boolean include = item.relevant() && selected < SELECTED;
            diagnostics.add(new Recall(current, item.score(), include));
            if (include) {
                selected++;
            }
        }
        return List.copyOf(diagnostics);
    }

    private List<Ranked> local(AiActor actor, String query, Set<String> libraries) {
        List<Ranked> segments = repository.localSources(actor, libraries).stream().map(this::evidence)
                .map(source -> rankLocal(query, source)).filter(Ranked::relevant)
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()).limit(KnowledgeIndex.CANDIDATES).toList();
        List<Ranked> pages = wiki.localCandidates(actor, libraries).stream().map(WikiService.Resolved::evidence)
                .map(source -> rankLocal(query, source)).filter(Ranked::relevant)
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()).limit(KnowledgeIndex.CANDIDATES).toList();
        var result = new ArrayList<>(pages);
        result.addAll(segments);
        return result;
    }

    private Ranked rankLocal(String query, Evidence evidence) {
        double score = KnowledgeIndex.lexical(query, evidence.title() + "\n" + evidence.heading() + "\n" + evidence.content());
        return new Ranked(evidence, score, score >= 0.2);
    }

    private List<Ranked> remote(AiActor actor, String query, Set<String> libraries) {
        List<KnowledgeIndex.Hit> hits = index.recall(actor.tenantId(), query, libraries);
        var ids = hits.stream().map(KnowledgeIndex.Hit::id).filter(id -> !id.startsWith("wiki:")).toList();
        Map<String, Evidence> byId = new LinkedHashMap<>();
        repository.sources(actor, ids, libraries).forEach(source -> byId.put(source.segment().id(), evidence(source)));
        List<Ranked> candidates = new ArrayList<>();
        for (var hit : hits) {
            Evidence evidence = hit.id().startsWith("wiki:") ? find(actor, hit.id(), libraries) : byId.get(hit.id());
            if (evidence != null) {
                candidates.add(new Ranked(evidence, hit.score(), true));
            }
        }
        return index.rerank(query, candidates);
    }

    public Evidence citation(AiActor actor, String evidenceId) {
        Evidence evidence = find(actor, evidenceId, null);
        if (evidence == null) {
            throw KnowledgeRepository.missing();
        }
        return evidence;
    }

    public boolean canRead(AiActor actor, String evidenceId, Set<String> libraryIds) {
        return find(actor, evidenceId, libraryIds) != null;
    }

    private Evidence find(AiActor actor, String evidenceId, Set<String> libraryIds) {
        if (evidenceId == null || evidenceId.isBlank() || evidenceId.length() > 80) {
            return null;
        }
        if (evidenceId.startsWith("wiki:")) {
            var result = wiki.publishedRevision(actor, evidenceId.substring(5), libraryIds);
            return result == null ? null : result.evidence();
        }
        return repository.sources(actor, List.of(evidenceId), libraryIds).stream().map(this::evidence).findFirst().orElse(null);
    }

    private Evidence evidence(Source source) {
        var segment = source.segment();
        return new Evidence(segment.id(), source.title(), segment.heading(), segment.content(), segment.pageFrom(), null,
                List.of(segment.id()), source.fingerprint());
    }

    public record Evidence(String segmentId, String title, String heading, String content, Integer page, String wikiSlug,
                           List<String> sourceIds, String fingerprint) {}
}
