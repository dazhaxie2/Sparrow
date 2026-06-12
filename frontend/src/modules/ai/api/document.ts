import { post } from '../../../shared/api/request'

export interface UploadResult {
  id: string
  filename: string
  status: string
}

export function uploadDocument(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return post<UploadResult>('/api/ai/documents/upload', formData)
}
