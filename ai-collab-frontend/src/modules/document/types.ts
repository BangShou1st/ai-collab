export type DocumentStatus =
  | 'UPLOADED'
  | 'PARSING'
  | 'INDEXING'
  | 'READY'
  | 'FAILED'
  | 'DELETING'

export interface ProjectDocument {
  id: string
  projectId: string
  displayName: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  status: DocumentStatus
  parserType: string | null
  chunkCount: number
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingDimension: number | null
  errorMessage: string | null
  uploadedById: string
  uploadedByDisplayName: string | null
  indexedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface DownloadUrl {
  url: string
  expiresAt: string
}
