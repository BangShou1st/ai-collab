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
  version: number
  createdAt: string
  updatedAt: string
}

export interface DownloadUrl {
  url: string
  expiresAt: string
}

export interface DocumentReadingStatus {
  bodyReadable: boolean
  searchAvailable: boolean
  failureStage: string | null
  snapshotId: string | null
  originalContentHash: string | null
  parseVersion: string | null
}
export interface DocumentBodyRead extends DocumentReadingStatus {
  items: Array<{ chunkId: string; chunkNo: number; heading: string | null; content: string; fromOffset: number; throughOffset: number; location?: { pageNumber?: number; pageThrough?: number } }>
  hasMore: boolean
  continuation: { snapshotId?: string | null; fromChunk: number; fromOffset: number } | null
}
