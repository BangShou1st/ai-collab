package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class P8EvidenceStrategyCounterexampleTest {
    @Test void bothFixedEightCandidatesLoseAnIndependentRequiredDocument() {
        UUID dominant=UUID.randomUUID();
        List<DocumentSearchHit> pool=new ArrayList<>();
        pool.add(hit(dominant,"主要事实",0.99));
        pool.add(hit(dominant,"互补事实",0.98));
        pool.add(hit(dominant,"第三事实",0.97));
        UUID minority=null;
        for(int i=0;i<7;i++) {
            minority=UUID.randomUUID();
            pool.add(hit(minority,"独立文档的必要条件"+i,0.8-i*0.05));
        }
        var builder=new KnowledgeContextBuilder();
        var production=builder.buildDiverse(pool,8);
        var s1=builder.build(P8RagQualityFixExperimentPostgresTest.protectFour(pool),8);
        var s2=builder.buildBalancedWithDiagnostics(pool,8).context();
        UUID required=minority;
        assertThat(production.sources()).anyMatch(s->s.documentId().equals(required));
        assertThat(s1.sources()).noneMatch(s->s.documentId().equals(required));
        assertThat(s2.sources()).noneMatch(s->s.documentId().equals(required));
    }
    @Test void queryWindowRetainsContiguousUnicodeOriginalWithUnchangedIdentity() {
        String body="😀步骤记录。".repeat(140)+"关键前提：必须完成发布审批后才允许继续发布。";
        String excerpt=P8RagQualityFixExperimentPostgresTest.queryWindow(body,"继续发布需要满足什么前提条件？");
        assertThat(body).contains(excerpt);
        assertThat(excerpt.codePointCount(0,excerpt.length())).isEqualTo(600);
        assertThat(excerpt).contains("必须完成发布审批后才允许继续发布");
        assertThat(P8RagQualityFixExperimentPostgresTest.prefix(body,600)).doesNotContain("必须完成发布审批");
    }
    private static DocumentSearchHit hit(UUID doc,String content,double score) {
        return new DocumentSearchHit(UUID.randomUUID(),doc,"document.md","section",content,content,score);
    }
}
