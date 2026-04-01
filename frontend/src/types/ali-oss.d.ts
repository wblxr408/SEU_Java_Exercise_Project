declare module 'ali-oss' {
  export interface Options {
    region: string
    accessKeyId: string
    accessKeySecret: string
    bucket: string
  }

  export interface PutResult {
    name: string
    url: string
    res: {
      status: number
    }
  }

  class OSS {
    constructor(options: Options)
    put(name: string, file: File | Blob | string): Promise<PutResult>
  }

  export default OSS
}
