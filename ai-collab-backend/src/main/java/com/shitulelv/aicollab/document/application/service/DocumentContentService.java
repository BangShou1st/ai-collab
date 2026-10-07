package com.shitulelv.aicollab.document.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Bounded body reads do not require the embedding gateway. All returned prose is untrusted source data. */
@Service
public class DocumentContentService {
    public static final String PARSE_VERSION="tika-page-clean-v2/chunk-offset-v2";
    /**
     * 正文分页预算（<b>字符</b>，不是 token）。工具 Schema 的默认值、执行边界的默认值与
     * 这里必须来自同一常量，避免"模型可见范围"与"执行要求"漂移。
     */
    public static final int DEFAULT_MAX_CHARS=12000;
    /** 单页正文预算上限（字符）。放宽窗口后仍必须有界，续读靠 continuation。 */
    public static final int MAX_MAX_CHARS=24000;
    /** 单页正文预算下限（字符）。 */
    public static final int MIN_MAX_CHARS=100;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessGuard access;
    public DocumentContentService(JdbcTemplate jdbc,ObjectMapper json,ProjectAccessGuard access) {
        this.jdbc=jdbc; this.json=json; this.access=access;
    }

    @Transactional
    public void saveParsed(UUID project,UUID document,UUID token,byte[] original,List<DocumentChunk> chunks) {
        var locked=jdbc.queryForList("SELECT id FROM project_document WHERE project_id=? AND id=? AND processing_token=? AND status='PARSING' FOR UPDATE",UUID.class,project,document,token);
        if(locked.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT);
        UUID snapshot=UUID.randomUUID();
        jdbc.update("DELETE FROM document_body WHERE document_id=?",document);
        jdbc.update("INSERT INTO document_body(document_id,snapshot_id,original_content_hash,parse_version) VALUES (?,?,?,?)",document,snapshot,hash(original),PARSE_VERSION);
        for(var chunk:chunks) jdbc.update("INSERT INTO document_body_chunk(id,document_id,chunk_no,heading,content,content_hash,metadata) VALUES (?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),document,chunk.chunkNo(),chunk.heading(),chunk.content(),chunk.contentHash(),json.valueToTree(chunk.metadata()).toString());
    }

    @Transactional(readOnly=true)
    public ObjectNode status(UUID project,UUID document,UUID user) {
        access.requireMember(project,user);
        var rows=jdbc.queryForList("""
                SELECT d.id,d.status,d.version,d.error_message,d.parser_type,b.snapshot_id,b.original_content_hash,b.parse_version,
                  (SELECT count(*) FROM document_body_chunk c WHERE c.document_id=d.id) AS body_count,
                  (SELECT count(*) FROM document_chunk c WHERE c.document_id=d.id) AS legacy_count
                FROM project_document d LEFT JOIN document_body b ON b.document_id=d.id WHERE d.project_id=? AND d.id=? AND d.status<>'DELETING'
                """,project,document);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND);
        var row=rows.getFirst(); var value=json.createObjectNode();
        value.put("documentId",document.toString());
        value.put("bodyReadable",((Number)row.get("body_count")).intValue()>0 || ((Number)row.get("legacy_count")).intValue()>0);
        value.put("searchAvailable","READY".equals(row.get("status")));
        value.put("processingStatus",row.get("status").toString());
        value.put("failureStage","FAILED".equals(row.get("status")) ? (row.get("snapshot_id")!=null ? "检索索引" : "正文解析或原件读取") : null);
        value.put("snapshotId",row.get("snapshot_id")==null ? null : row.get("snapshot_id").toString());
        value.put("originalContentHash",(String)row.get("original_content_hash"));
        value.put("parseVersion",(String)row.get("parse_version"));
        value.put("recordVersion",((Number)row.get("version")).intValue());
        value.put("legacySource",row.get("snapshot_id")==null);
        return value;
    }

    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ObjectNode read(UUID project,UUID document,UUID user,UUID snapshot,Integer from,int fromOffset,int maxChars,String heading,UUID chunkId) {
        if(from==null || from<0 || fromOffset<0 || maxChars<MIN_MAX_CHARS || maxChars>MAX_MAX_CHARS || (heading!=null && heading.length()>300)) throw new IllegalArgumentException("文档范围或预算无效");
        var state=status(project,document,user);
        if(snapshot!=null && !snapshot.toString().equals(state.path("snapshotId").asText())) throw new BusinessException(ErrorCode.DOCUMENT_PROCESSING_CONFLICT,"来源已更新，请重新选择正文");
        boolean legacy=state.path("legacySource").asBoolean();
        String table=legacy ? "document_chunk" : "document_body_chunk";
        var chunks=jdbc.query("SELECT id,chunk_no,heading,content,content_hash,metadata::text FROM "+table+" WHERE document_id=? AND chunk_no>=? ORDER BY chunk_no",(rs,n)-> {
            var item=json.createObjectNode(); item.put("chunkId",rs.getObject("id").toString()); item.put("chunkNo",rs.getInt("chunk_no")); item.put("heading",rs.getString("heading")); item.put("content",rs.getString("content")); item.put("contentHash",rs.getString("content_hash"));
            try { item.set("location",json.readTree(rs.getString("metadata"))); } catch(Exception failure) { throw new IllegalStateException(failure); } return item;
        },document,from);
        // A historical search citation can belong to the embedding table; read that exact immutable ID, never substitute by chunk number.
        if(chunkId!=null) {
            chunks=jdbc.query("SELECT id,chunk_no,heading,content,content_hash,metadata::text FROM document_chunk WHERE project_id=? AND document_id=? AND id=? UNION ALL SELECT c.id,c.chunk_no,c.heading,c.content,c.content_hash,c.metadata::text FROM document_body_chunk c JOIN project_document d ON d.id=c.document_id WHERE d.project_id=? AND c.document_id=? AND c.id=?",(rs,n)-> {
                var item=json.createObjectNode().put("chunkId",rs.getObject(1).toString()).put("chunkNo",rs.getInt(2)).put("heading",rs.getString(3)).put("content",rs.getString(4)).put("contentHash",rs.getString(5));
                try {item.set("location",json.readTree(rs.getString(6)));} catch(Exception ex){throw new IllegalStateException(ex);} return item;
            },project,document,chunkId,project,document,chunkId);
            if(chunks.isEmpty()) throw new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND,"引用来源已更新或已删除");
        }
        var result=state.deepCopy(); var items=result.putArray("items"); int used=0; Integer next=null; int nextOffset=0;
        for(var chunk:chunks) {
            if(heading!=null && !heading.equals(chunk.path("heading").asText())) continue;
            String content=chunk.path("content").asText(); int offset=chunk.path("chunkNo").asInt()==from ? fromOffset : 0;
            if(offset>content.length()) throw new IllegalArgumentException("续读偏移超过正文");
            int take=Math.min(maxChars-used,content.length()-offset);
            if(take>0 && offset+take<content.length() && Character.isHighSurrogate(content.charAt(offset+take-1))) take--;
            if(take==0) { next=chunk.path("chunkNo").asInt(); nextOffset=offset; break; }
            var item=chunk.deepCopy(); item.put("content",content.substring(offset,offset+take)); item.put("fromOffset",offset); item.put("throughOffset",offset+take); items.add(item); used+=take;
            if(offset+take<content.length()) {next=chunk.path("chunkNo").asInt();nextOffset=offset+take;break;}
        }
        result.put("readChars",used); result.put("coverage","SPECIFIED_RANGE_ONLY"); result.put("truncated",next!=null); result.put("hasMore",next!=null);
        if(next!=null) result.putObject("continuation").put("fromChunk",next).put("fromOffset",nextOffset).put("snapshotId",state.path("snapshotId").asText(null));
        else result.putNull("continuation");
        result.put("trust","SOURCE_DATA_ONLY"); return result;
    }

    @Transactional(readOnly=true)
    public ObjectNode outline(UUID project,UUID document,UUID user) {
        var result=status(project,document,user); String table=result.path("legacySource").asBoolean() ? "document_chunk" : "document_body_chunk";
        result.set("sections",json.valueToTree(jdbc.queryForList("SELECT heading,min(chunk_no) AS from_chunk,max(chunk_no) AS through_chunk FROM "+table+" WHERE document_id=? GROUP BY heading ORDER BY min(chunk_no) LIMIT 101",document)));
        result.put("structure","HEURISTIC_HEADINGS"); result.put("truncated",result.path("sections").size()>100); return result;
    }

    private static String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch(Exception ex){ throw new IllegalStateException(ex); }
    }
}
