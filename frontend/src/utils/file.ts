export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

export function isAcceptedFileType(file: File, accept: string): boolean {
  const ext = '.' + file.name.split('.').pop()?.toLowerCase()
  const types = accept.split(',').map((t) => t.trim().toLowerCase())
  return types.includes(ext) || types.includes(file.type)
}
