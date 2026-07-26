package com.shitulelv.aicollab.knowledge.infrastructure.entity;

import java.util.UUID;

public class KnowledgeCitationEntity {
    private UUID id;
    private UUID messageId;
    private UUID chunkId;
    private UUID documentId;
    private String filename;
    private String heading;
    private int rank;
    private double similarity;
    private String quote;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMessageId() { return messageId; }
    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public UUID getChunkId() { return chunkId; }
    public void setChunkId(UUID chunkId) { this.chunkId = chunkId; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getHeading() { return heading; }
    public void setHeading(String heading) { this.heading = heading; }
    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }
    public double getSimilarity() { return similarity; }
    public void setSimilarity(double similarity) { this.similarity = similarity; }
    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }
}
