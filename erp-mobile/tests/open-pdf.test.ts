import { afterEach, expect, it, vi } from 'vitest'
import { openPdf } from '../src/utils/openPdf'

afterEach(() => vi.unstubAllGlobals())

function configureAndroid(pdf: ArrayBuffer, savedSize = pdf.byteLength) {
  const absolutePath = '/data/user/0/app/doc/label.pdf'
  const bytes = { type: 'byte[]' }
  const stream = { type: 'FileOutputStream' }
  const invoke = vi.fn((_object: unknown, method: string) => method === 'decode' ? bytes : undefined)
  const getAttribute = vi.fn(() => 0)
  const newObject = vi.fn(() => stream)
  const openDocument = vi.fn(({ success }: { success: () => void }) => success())
  const convertLocalFileSystemURL = vi.fn(() => absolutePath)
  const getFileInfo = vi.fn(({ success }: { success: (file: { size: number }) => void }) => success({ size: savedSize }))
  vi.stubGlobal('uni', {
    getSystemInfoSync: () => ({ uniPlatform: 'app' }),
    arrayBufferToBase64: (input: ArrayBuffer) => Buffer.from(input).toString('base64'),
    openDocument,
  })
  vi.stubGlobal('plus', {
    os: { name: 'Android' },
    android: { getAttribute, invoke, newObject },
    io: { convertLocalFileSystemURL, getFileInfo },
  })
  return { absolutePath, bytes, stream, getAttribute, invoke, newObject, openDocument, convertLocalFileSystemURL }
}

it('Android 保存已鉴权下载的 PDF 原始字节，再使用本地路径打开', async () => {
  const pdf = new TextEncoder().encode('%PDF-1.7\n').buffer
  const native = configureAndroid(pdf)
  await openPdf(pdf)
  expect(native.getAttribute).toHaveBeenCalledWith('android.util.Base64', 'DEFAULT')
  expect(native.invoke).toHaveBeenNthCalledWith(1, 'android.util.Base64', 'decode', 'JVBERi0xLjcK', 0)
  expect(native.convertLocalFileSystemURL).toHaveBeenCalledWith(expect.stringMatching(/^_doc\/erp-label-\d+\.pdf$/))
  expect(native.newObject).toHaveBeenCalledWith('java.io.FileOutputStream', native.absolutePath)
  expect(native.invoke).toHaveBeenNthCalledWith(2, native.stream, 'write', native.bytes)
  expect(native.invoke).toHaveBeenNthCalledWith(3, native.stream, 'close')
  expect(native.openDocument).toHaveBeenCalledWith(expect.objectContaining({ filePath: native.absolutePath, fileType: 'pdf' }))
})

it('PDF 保存不完整时提示错误并留在 App', async () => {
  const pdf = new TextEncoder().encode('%PDF-1.7\n').buffer
  const native = configureAndroid(pdf, 0)
  await expect(openPdf(pdf)).rejects.toThrow('保存不完整')
  expect(native.openDocument).not.toHaveBeenCalled()
})
