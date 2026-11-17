import type { IScannerControls } from '@zxing/browser'

export function nativeScan(): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.scanCode({
      onlyFromCamera: true,
      success: (result) => resolve(result.result),
      fail: () => reject(new Error('扫码已取消或摄像头不可用')),
    })
  })
}

export async function cameraScan(containerId: string, signal: AbortSignal): Promise<string> {
  // #ifdef H5
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia)
    throw new Error('请在支持摄像头的 HTTPS 页面扫码，或使用扫码枪')
  const container = document.getElementById(containerId)
  if (!container) throw new Error('摄像头预览尚未就绪')
  const { BrowserMultiFormatReader } = await import('@zxing/browser')
  if (signal.aborted) throw new Error('扫码已取消')
  const video = document.createElement('video')
  video.autoplay = true
  video.muted = true
  video.playsInline = true
  video.style.width = '100%'
  container.append(video)
  let controls: IScannerControls | undefined
  try {
    return await new Promise<string>((resolve, reject) => {
      const reader = new BrowserMultiFormatReader()
      signal.addEventListener('abort', () => reject(new Error('扫码已取消')), { once: true })
      void reader.decodeFromConstraints(
        { audio: false, video: { facingMode: { ideal: 'environment' } } },
        video,
        (result) => {
          if (result) resolve(result.getText())
        },
      ).then((value) => {
        controls = value
        if (signal.aborted || !video.isConnected) controls.stop()
      }, reject)
    })
  } finally {
    controls?.stop()
    const stream = video.srcObject
    if (stream instanceof MediaStream) stream.getTracks().forEach((track) => track.stop())
    video.remove()
  }
  // #endif
  // #ifndef H5
  throw new Error('当前平台请使用原生相机扫码')
  // #endif
}

export function feedback(ok: boolean): void {
  if (!ok && typeof uni.vibrateLong === 'function') uni.vibrateLong({ fail: () => undefined })
  if (ok && typeof uni.vibrateShort === 'function') uni.vibrateShort({ fail: () => undefined })
  if (typeof uni.createInnerAudioContext !== 'function') return
  const sound = uni.createInnerAudioContext()
  const file = ok ? 'scan-ok.wav' : 'scan-error.wav'
  // #ifdef H5
  sound.src = '/mobile/static/' + file
  // #endif
  // #ifndef H5
  sound.src = '/static/' + file
  // #endif
  sound.onEnded(() => sound.destroy())
  sound.onError(() => sound.destroy())
  sound.play()
}
