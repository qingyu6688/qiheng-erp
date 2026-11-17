import { nativeApp } from '../stores/session'

const ANDROID_BASE64 = 'android.util.Base64'

async function openAndroidPdf(name: string, data: ArrayBuffer): Promise<void> {
  if (typeof plus === 'undefined' || plus.os.name !== 'Android')
    throw new Error('请在 Android App 中打开标签 PDF')

  const localPath = '_doc/' + name
  const path = plus.io.convertLocalFileSystemURL(localPath)
  if (!path) throw new Error('无法访问 App 私有文档目录')

  const invoke: (target: string | PlusAndroidInstanceObject, method: string, ...args: unknown[]) => unknown =
    plus.android.invoke.bind(plus.android)
  try {
    const flags: unknown = plus.android.getAttribute(ANDROID_BASE64, 'DEFAULT')
    const bytes = invoke(ANDROID_BASE64, 'decode', uni.arrayBufferToBase64(data), flags)
    if (bytes == null) throw new Error('标签 PDF 转换失败')
    const stream = plus.android.newObject('java.io.FileOutputStream', path)
    if (!stream) throw new Error('无法创建标签 PDF 文件')
    try {
      invoke(stream, 'write', bytes)
    } finally {
      invoke(stream, 'close')
    }
  } catch (cause: unknown) {
    throw new Error('设备无法保存标签 PDF', { cause })
  }

  // 原生桥接调用失败可能没有异常，核对实际文件长度后再交给阅读器。
  await new Promise<void>((resolve, reject) => {
    const failed = (): void => reject(new Error('标签 PDF 保存不完整，请重新生成'))
    plus.io.getFileInfo({
      filePath: localPath,
      success: (file: PlusIoFileInfo) => file.size === data.byteLength ? resolve() : failed(),
      fail: failed,
    })
  })

  await new Promise<void>((resolve, reject) => {
    uni.openDocument({
      filePath: path, fileType: 'pdf', showMenu: true, success: () => resolve(),
      fail: () => reject(new Error('设备未能打开标签 PDF，请检查 PDF 阅读应用')),
    })
  })
}

export async function openPdf(data: ArrayBuffer): Promise<void> {
  if (data.byteLength < 5 || String.fromCharCode(...new Uint8Array(data, 0, 5)) !== '%PDF-')
    throw new Error('ERP 未返回有效的 PDF 文件')
  const name = 'erp-label-' + Date.now() + '.pdf'
  if (nativeApp()) {
    await openAndroidPdf(name, data)
    return
  }
  const url = URL.createObjectURL(new Blob([data], { type: 'application/pdf' }))
  const link = document.createElement('a')
  link.href = url
  link.download = name
  document.body.append(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 5000)
}
