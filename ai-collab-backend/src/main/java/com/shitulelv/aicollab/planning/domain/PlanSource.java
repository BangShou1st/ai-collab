package com.shitulelv.aicollab.planning.domain;

import java.util.UUID;

public record PlanSource(String ref, UUID documentId, String documentName, String quoteText) {}
