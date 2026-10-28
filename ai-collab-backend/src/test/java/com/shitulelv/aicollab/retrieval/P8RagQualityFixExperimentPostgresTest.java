package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.AfterAll;
import org.springframework.beans.factory.annotation.Autowired;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Test-only paired experiments. Strategies never receive fact labels or diagnostic full rankings. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="AI_REAL_OLLAMA_RETRIEVAL",matches="true")
class P8RagQualityFixExperimentPostgresTest extends P6RagQualityBaselinePostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private final KnowledgeContextBuilder contexts = new KnowledgeContextBuilder();
    private final Map<String,String> plans = new LinkedHashMap<>();
    @Autowired ProjectAccessGuard guard;

    @Override
    protected void observeQuestion(JsonNode question, Map<String, Object> baseline) throws Exception {
        String query = question.path("query").asText();
        List<UUID> scope = new ArrayList<>();
        question.path("scope").forEach(name -> scope.add(documentIds.get(name.asText())));
        guard.requireMember(project, user);
        long started = System.nanoTime();
        var qa = search.search(project, query, scope, 8);
        var agent = search.search(project, query, scope, 20);
        var vector = embeddings.embed(project, List.of(query), EmbeddingProgressListener.NONE);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", question.path("id").asText());
        row.put("query", query);
        row.put("scope", scope);
        List<Map<String, Object>> variants = new ArrayList<>();
        variants.add(measure("S0", contexts.buildDiverse(agent, 8), baseline, true, query, false));
        // S1 protects four representative documents, leaving four slots for globally relevant complements.
        List<DocumentSearchHit> protectedFirst = protectFour(agent);
        variants.add(measure("S1_PROTECT_4", contexts.build(protectedFirst, 8), baseline, true, query, false));
        variants.add(measure("S2_CAP_2", contexts.buildBalancedWithDiagnostics(agent, 8).context(), baseline, true, query, false));
        variants.add(measure("S3_LIMIT_12", contexts.buildDiverse(agent, 12), baseline, true, query, false));
        List<Map<String, Object>> retrieval = new ArrayList<>();
        for (boolean heading : List.of(false, true)) {
            for (boolean qaPath : List.of(true, false)) {
                var original = qaPath ? qa : agent;
                long sqlStarted = System.nanoTime();
                var extra = supplement(original, query, vector.vectors().getFirst().toString(),
                        vector.provider(), vector.model(), vector.dimension(), vector.fingerprint(),
                        vector.generationId(), heading);
                long sqlMicros = (System.nanoTime() - sqlStarted) / 1000;
                assertThat(extra).hasSizeLessThanOrEqualTo(4);
                assertThat(extra).allSatisfy(h -> {
                    assertThat(original).anyMatch(p -> p.documentId().equals(h.documentId()));
                    if (!scope.isEmpty()) assertThat(scope).contains(h.documentId());
                });
                List<DocumentSearchHit> merged = new ArrayList<>(original);
                merged.addAll(extra);
                merged.sort(Comparator.comparingDouble(DocumentSearchHit::similarity).reversed().thenComparing(DocumentSearchHit::id));
                KnowledgeContext normal = qaPath ? contexts.buildBalancedWithDiagnostics(merged, 5).context()
                        : contexts.buildDiverse(merged, 8);
                // Separate research arm: reserve one position for the supplement without falsifying its score.
                List<DocumentSearchHit> reserved = new ArrayList<>(extra.stream().limit(1).toList());
                for (var h : original) if (reserved.stream().noneMatch(p -> p.id().equals(h.id()))) reserved.add(h);
                KnowledgeContext promoted = contexts.build(reserved, qaPath ? 5 : 8);
                Map<String,Object> r = new LinkedHashMap<>();
                r.put("strategy", heading ? "R2_HEADING_BIGRAM" : "R1_NEIGHBOR");
                r.put("path", qaPath ? "QA" : "AGENT");
                r.put("supplement", extra);
                r.put("candidateCount", merged.size());
                r.put("extraSqlCount", 1);
                r.put("sqlMicros", sqlMicros);
                r.put("normal", measure("NORMAL", normal, baseline, !qaPath, query, false));
                r.put("reserveOnePrefix", measure("RESERVE_ONE_PREFIX", promoted, baseline, !qaPath, query, false));
                r.put("reserveOneWindow", measure("RESERVE_ONE_WINDOW", promoted, baseline, !qaPath, query, true));
                retrieval.add(r);
            }
        }
        row.put("selection", variants);
        row.put("retrieval", retrieval);
        row.put("experimentLatencyMs", (System.nanoTime() - started) / 1_000_000);
        row.put("additionalVectorGatewayInvocationsForHarness", 3);
        row.put("ftsSimple", jdbc.queryForObject("SELECT to_tsvector('simple', ?)::text", String.class, query));
        if (RESULTS.isEmpty()) {
            List<String> checked=new ArrayList<>();
            for (boolean heading : List.of(false,true)) {
                String v=vector.vectors().getFirst().toString();
                assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,vector.fingerprint(),vector.generationId(),heading)).isNotEmpty();
                assertThat(supplement(qa,query,v,"WRONG",vector.model(),1024,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                assertThat(supplement(qa,query,v,vector.provider(),"WRONG",1024,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                assertThat(supplement(qa,query,v,vector.provider(),vector.model(),3,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,"WRONG",vector.generationId(),heading)).isEmpty();
                assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,vector.fingerprint(),UUID.randomUUID(),heading)).isEmpty();
                UUID legitimateUser=user;
                try {
                    user=UUID.randomUUID();
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->guard.requireMember(project,user))
                            .isInstanceOf(com.shitulelv.aicollab.common.exception.BusinessException.class);
                    assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                } finally {user=legitimateUser;}
                UUID legitimateProject=project;
                try {
                    project=UUID.randomUUID();
                    assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                } finally {project=legitimateProject;}
                List<UUID> checkedDocs=qa.stream().map(DocumentSearchHit::documentId).distinct().limit(2).toList();
                for (UUID doc:checkedDocs)jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?",doc);
                try {
                    assertThat(supplement(qa,query,v,vector.provider(),vector.model(),1024,vector.fingerprint(),vector.generationId(),heading)).isEmpty();
                } finally {for(UUID doc:checkedDocs)jdbc.update("UPDATE project_document SET status='READY' WHERE id=?",doc);}
                checked.add((heading?"R2":"R1")+":provider/model/dimension/fingerprint/generation/member/project/READY");
            }
            row.put("securityFilterChecks",checked);
            row.put("diagnosticQueryPlans",plans);
        }
        RESULTS.add(row);
    }

    static List<DocumentSearchHit> protectFour(List<DocumentSearchHit> hits) {
        var representatives = new LinkedHashMap<UUID, DocumentSearchHit>();
        hits.forEach(hit -> representatives.putIfAbsent(hit.documentId(), hit));
        List<DocumentSearchHit> result = new ArrayList<>(representatives.values().stream().limit(4).toList());
        hits.stream().filter(h -> result.stream().noneMatch(p -> p.id().equals(h.id()))).forEach(result::add);
        return result;
    }

    /** One parameterized query; at most two already-authorized documents, four additional chunks.
     * Excludes every original candidate. No answer labels, filenames, question IDs or full ranking input.
     */
    private List<DocumentSearchHit> supplement(List<DocumentSearchHit> original, String query, String vector,
            String provider, String model, int dimension, String fingerprint, UUID generation, boolean heading) {
        var anchors = new LinkedHashMap<UUID, UUID>();
        original.forEach(h -> anchors.putIfAbsent(h.documentId(), h.id()));
        var docs = anchors.keySet().stream().limit(2).toList();
        String order = heading ? """
                (SELECT count(DISTINCT substring(c.heading FROM n FOR 2))
                 FROM generate_series(1, least(length(c.heading)-1,150)) n
                 WHERE strpos(?, substring(c.heading FROM n FOR 2))>0) DESC,
                """ : """
                abs(c.chunk_no - (SELECT a.chunk_no FROM document_chunk a
                     WHERE a.id=ANY(?::uuid[]) AND a.document_id=c.document_id LIMIT 1)),
                """;
        // Only fixed SQL fragments are assembled; every data value is a JDBC parameter.
        String sql = """
                SELECT c.id,c.document_id,d.original_filename,c.heading,c.content,c.content_hash,
                       1-(c.embedding <=> ?::vector) similarity
                FROM document_chunk c JOIN project_document d ON d.id=c.document_id AND d.project_id=c.project_id
                WHERE c.project_id=? AND c.document_id=ANY(?::uuid[]) AND d.status='READY'
                  AND c.id<>ALL(?::uuid[]) AND c.embedding_provider=? AND c.embedding_model=?
                  AND c.embedding_dimension=? AND c.embedding_fingerprint=?
                  AND c.generation_id IS NOT DISTINCT FROM ?::uuid
                  AND EXISTS(SELECT 1 FROM project_member m WHERE m.project_id=c.project_id AND m.user_id=?)
                ORDER BY
                """ + order + " similarity DESC,c.id LIMIT 4";
        Object[] args={vector,project,pgArray(docs),pgArray(original.stream().map(DocumentSearchHit::id).toList()),
                provider,model,dimension,fingerprint,generation,user,
                heading ? query : pgArray(docs.stream().map(anchors::get).toList())};
        String planKey=(heading?"R2":"R1")+"-"+original.size();
        if (!plans.containsKey(planKey)) plans.put(planKey,
                jdbc.queryForObject("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "+sql,String.class,args));
        return jdbc.query(sql, (rs, n) -> new DocumentSearchHit(rs.getObject("id",UUID.class),
                rs.getObject("document_id",UUID.class),rs.getString("original_filename"),rs.getString("heading"),
                rs.getString("content"),rs.getString("content_hash"),rs.getDouble("similarity")),
                args);
    }

    private static String pgArray(Collection<UUID> values) {
        return "{" + String.join(",",values.stream().map(UUID::toString).toList()) + "}";
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> measure(String name, KnowledgeContext context, Map<String,Object> baseline,
            boolean agent, String query, boolean window) {
        List<Map<String,Object>> sources = new ArrayList<>();
        for (var source : context.sources()) {
            String body = source.content();
            String quote = agent ? (window ? queryWindow(body,query) : prefix(body,600)) : body;
            assertThat(body).contains(quote);
            if (agent) assertThat(cp(quote)).isLessThanOrEqualTo(600);
            sources.add(Map.of("documentId",source.documentId(),"chunkId",source.chunkId(),
                    "filename",source.originalFilename(),"heading",source.heading()==null?"":source.heading(),
                    "quote",quote,"contextBody",body,"originalContentHash",source.contentHash(),
                    "excerptStartUtf16",body.indexOf(quote),"excerptEndUtf16",body.indexOf(quote)+quote.length()));
        }
        assertThat(cp(context.promptSources())).isLessThanOrEqualTo(8000);
        List<Map<String,Object>> facts = new ArrayList<>();
        for (var fact : (List<Map<String,Object>>)baseline.get("facts")) {
            String marker=fact.get("marker").toString();
            String chunk=String.valueOf(fact.get("chunkId"));
            boolean selected=sources.stream().anyMatch(s -> s.get("chunkId").toString().equals(chunk));
            boolean retained=sources.stream().anyMatch(s -> s.get("chunkId").toString().equals(chunk) && s.get("quote").toString().contains(marker));
            facts.add(Map.of("marker",marker,"chunkId",chunk,"baseline",fact.get(agent?"agentStage":"qaStage"),
                    "stage",retained?"RETAINED":selected?"BODY_TRUNCATION":"SELECTION_OR_RETRIEVAL_MISS"));
        }
        return Map.of("strategy",name,"sources",sources,"facts",facts,
                "documentCoverage",sources.stream().map(s->s.get("documentId")).distinct().count(),
                "contextCodePoints",cp(context.promptSources()),
                "quoteCodePoints",sources.stream().mapToInt(s->cp(s.get("quote").toString())).sum());
    }

    /** A single contiguous window, scored solely with distinct original-query Han bigrams.
     * Sentence ends are preferred; there is no concatenation or mutation of original body/hash.
     */
    static String queryWindow(String body,String query) {
        if(cp(body)<=600)return body;
        Set<String> terms=new HashSet<>();
        var matcher=Pattern.compile("[\\p{IsHan}]+").matcher(query);
        while(matcher.find()) {String text=matcher.group();for(int i=0;i<text.length()-1;i++)terms.add(text.substring(i,i+2));}
        int bestScore=-1,bestStart=0;
        for(int end=600;end<=cp(body);end++) {
            int utfEnd=body.offsetByCodePoints(0,end);
            if(end!=cp(body) && "。！？；\n".indexOf(body.charAt(utfEnd-1))<0)continue;
            int start=end-600;
            String candidate=body.substring(body.offsetByCodePoints(0,start),utfEnd);
            int score=(int)terms.stream().filter(candidate::contains).count();
            if(score>bestScore) {bestScore=score;bestStart=start;}
        }
        return body.substring(body.offsetByCodePoints(0,bestStart),body.offsetByCodePoints(0,bestStart+600));
    }
    static String prefix(String s,int n) {return cp(s)<=n?s:s.substring(0,s.offsetByCodePoints(0,n));}
    static int cp(String s) {return s.codePointCount(0,s.length());}

    @AfterAll static void save() throws Exception {
        Path out=Path.of(System.getProperty("p8.fix.outputDir","target/p8-rag-quality-fix-01"),"paired-experiments.json");
        Files.createDirectories(out.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), RESULTS);
    }
}
