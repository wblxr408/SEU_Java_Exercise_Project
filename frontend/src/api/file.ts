/**
 * 文件上传 API
 * 优先使用阿里云OSS前端直传（省流量）
 * 若未配置OSS则回退到后端中转上传
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'

const uploadInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 60000,
  paramsSerializer: (params) => {
    const parts: string[] = []
    for (const key of Object.keys(params)) {
      const values = params[key]
      if (Array.isArray(values)) {
        values.forEach((v) => parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(v)}`))
      } else {
        parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(values)}`)
      }
    }
    return parts.join('&')
  },
})

uploadInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

uploadInstance.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 413) {
      ElMessage.error('文件过大，单张图片不能超过10MB')
    } else if (error.response?.data?.message) {
      ElMessage.error(error.response.data.message)
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('上传超时，请检查网络')
    } else {
      ElMessage.error(error.message || '上传失败')
    }
    return Promise.reject(error)
  }
)

export interface ImageUploadResponse {
  url: string
  originalFilename?: string
  size?: number
}

// ==================== 阿里云OSS直传（推荐）====================

type OSS = import('ali-oss').default

let ossClient: OSS | null = null

async function getOssClient(): Promise<OSS> {
  if (ossClient) return ossClient

  const OSS = (await import('ali-oss')).default

  ossClient = new OSS({
    region: import.meta.env.VITE_OSS_REGION || 'cn-hangzhou',
    accessKeyId: import.meta.env.VITE_OSS_ACCESS_KEY_ID!,
    accessKeySecret: import.meta.env.VITE_OSS_ACCESS_KEY_SECRET!,
    bucket: import.meta.env.VITE_OSS_BUCKET!,
  })

  return ossClient
}

function buildOssObjectKey(filename: string): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  const ext = filename.includes('.') ? filename.substring(filename.lastIndexOf('.')) : ''
  const uuid = crypto.randomUUID().replace(/-/g, '')
  return `images/${year}/${month}/${day}/${uuid}${ext}`
}

async function uploadToOss(file: File): Promise<string> {
  const client = await getOssClient()
  const objectKey = buildOssObjectKey(file.name)

  await client.put(objectKey, file)

  const domain = import.meta.env.VITE_OSS_DOMAIN
  if (domain) {
    return (domain.replace(/\/$/, '') + '/' + objectKey)
  }
  return `https://${import.meta.env.VITE_OSS_BUCKET}.oss-${import.meta.env.VITE_OSS_REGION}.aliyuncs.com/${objectKey}`
}

function isOssEnabled(): boolean {
  return (
    import.meta.env.VITE_OSS_ENABLED === 'true' &&
    import.meta.env.VITE_OSS_BUCKET &&
    import.meta.env.VITE_OSS_ACCESS_KEY_ID &&
    import.meta.env.VITE_OSS_ACCESS_KEY_SECRET
  )
}

// ==================== 后端中转上传（备选）====================

async function uploadToBackend(files: File[]): Promise<ImageUploadResponse[]> {
  const formData = new FormData()
  files.forEach((file) => formData.append('files', file))
  const res = await uploadInstance.post('/file/upload/batch', formData)
  return res.data as ImageUploadResponse[]
}

async function registerUrls(urls: string[]): Promise<ImageUploadResponse[]> {
  const res = await uploadInstance.post('/file/register-url/batch', null, {
    params: { urls }
  })
  return res.data as ImageUploadResponse[]
}

// ==================== 公开 API =====================

/**
 * 上传单张图片（根据配置自动选择OSS直传或后端中转）
 */
export async function uploadImage(file: File): Promise<ImageUploadResponse> {
  if (isOssEnabled()) {
    const url = await uploadToOss(file)
    await registerUrls([url])
    return { url, originalFilename: file.name, size: file.size }
  } else {
    const results = await uploadToBackend([file])
    return results[0]
  }
}

/**
 * 批量上传图片（根据配置自动选择OSS直传或后端中转）
 * 推荐使用此接口
 */
export async function uploadImages(files: File[]): Promise<ImageUploadResponse[]> {
  if (isOssEnabled()) {
    const urls: string[] = []
    for (const file of files) {
      urls.push(await uploadToOss(file))
    }
    return await registerUrls(urls)
  } else {
    return await uploadToBackend(files)
  }
}

// ==================== 纯后端接口（保留给不需要OSS的场景）====================

/**
 * 上传单张图片（后端中转）
 */
export function uploadImageViaBackend(file: File): Promise<ImageUploadResponse> {
  const formData = new FormData()
  formData.append('file', file)
  return uploadInstance.post('/file/upload', formData).then((res) => res.data)
}

/**
 * 批量上传图片（后端中转）
 */
export function uploadImagesViaBackend(files: File[]): Promise<ImageUploadResponse[]> {
  const formData = new FormData()
  files.forEach((file) => formData.append('files', file))
  return uploadInstance.post('/file/upload/batch', formData).then((res) => res.data)
}

/**
 * 注册外部图片URL（前端已上传至OSS等，URL直接入库）
 * 推荐使用此接口（省流量）
 */
export function registerImageUrl(url: string): Promise<ImageUploadResponse> {
  return uploadInstance.post('/file/register-url', null, {
    params: { url }
  }).then((res) => res.data)
}

/**
 * 批量注册外部图片URL（推荐，批量更高效）
 */
export function registerImageUrls(urls: string[]): Promise<ImageUploadResponse[]> {
  return uploadInstance.post('/file/register-url/batch', null, {
    params: { urls }
  }).then((res) => res.data)
}
