import axios from 'axios'
import { apiResultFromResponse } from './api-result'
import type { ApiResponse, ApiResult } from './types'

export interface PublicSystemStatus {
  maintenanceMode: boolean
  maintenanceMessage: string
  publicRegistrationEnabled: boolean
}

const statusHttpClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  withCredentials: true,
})

export const systemStatusApi = {
  async getStatus(): Promise<ApiResult<PublicSystemStatus>> {
    return apiResultFromResponse(
      await statusHttpClient.get<ApiResponse<PublicSystemStatus>>('/system/status'),
    )
  },
}
