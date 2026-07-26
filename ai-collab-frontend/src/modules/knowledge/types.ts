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
  answer: string
  insufficientEvidence: boolean
  model: string | null
  citations: KnowledgeCitation[]
}
