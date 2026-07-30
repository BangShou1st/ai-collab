package com.shitulelv.aicollab.knowledge.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeEvalResultEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeEvalRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface KnowledgeEvalMapper extends BaseMapper<KnowledgeEvalRunEntity> {

    @Select("SELECT * FROM knowledge_eval_run WHERE project_id = #{projectId} ORDER BY created_at DESC LIMIT 20")
    List<KnowledgeEvalRunEntity> listRuns(@Param("projectId") UUID projectId);

    @Select("SELECT * FROM knowledge_eval_run WHERE id = #{id} AND project_id = #{projectId}")
    KnowledgeEvalRunEntity findRun(@Param("id") UUID id, @Param("projectId") UUID projectId);

    @Insert("""
            INSERT INTO knowledge_eval_result(id, run_id, question, expected_document_ids,
              retrieved_document_ids, recall_at_3, recall_at_5, mrr, created_at)
            VALUES(#{id}, #{runId}, #{question}, CAST(#{expectedDocumentIds} AS jsonb),
              CAST(#{retrievedDocumentIds} AS jsonb), #{recallAt3}, #{recallAt5}, #{mrr}, #{createdAt})
            """)
    int insertResult(@Param("id") UUID id, @Param("runId") UUID runId,
                     @Param("question") String question,
                     @Param("expectedDocumentIds") String expectedDocumentIds,
                     @Param("retrievedDocumentIds") String retrievedDocumentIds,
                     @Param("recallAt3") java.math.BigDecimal recallAt3,
                     @Param("recallAt5") java.math.BigDecimal recallAt5,
                     @Param("mrr") java.math.BigDecimal mrr,
                     @Param("createdAt") java.time.OffsetDateTime createdAt);

    @Select("SELECT * FROM knowledge_eval_result WHERE run_id = #{runId} ORDER BY created_at ASC")
    List<KnowledgeEvalResultEntity> listResults(@Param("runId") UUID runId);
}
