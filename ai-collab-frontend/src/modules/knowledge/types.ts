export interface KnowledgeSession {
  id: string
  projectId: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface KnowledgeCitation {
  documentId: string
  filename: string
  chunkId: string
  heading: string | null
  quote: string
  similarity: number
  rank: number
  pageNumber: number | null
}

export interface KnowledgeMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  insufficientEvidence: boolean
  model: string | null
  citations: KnowledgeCitation[]
  createdAt: string
}

export interface KnowledgeSessionDetail {
  session: KnowledgeSession
  messages: KnowledgeMessage[]
}

export interface KnowledgeAnswer {
  messageId: string
  answer: string
  insufficientEvidence: boolean
  model: string | null
  citations: KnowledgeCitation[]
}

export interface KnowledgeFeedback {
  myFeedback: boolean | null
  helpfulCount: number
  unhelpfulCount: number
}

export interface KnowledgeEvalRun {
  id: string
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  totalQuestions: number
  recallAt3: number | null
  recallAt5: number | null
  mrr: number | null
  avgSimilarity: number | null
  createdAt: string
  completedAt: string | null
}

export interface KnowledgeEvalResult {
  question: string
  expectedDocumentIds: string[]
  retrievedDocumentIds: string[]
  recallAt3: number | null
  recallAt5: number | null
  mrr: number | null
}

export interface KnowledgeEvalRunDetail {
  run: KnowledgeEvalRun
  results: KnowledgeEvalResult[]
}
