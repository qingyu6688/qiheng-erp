import { request } from './http'

interface DeviceInvitation {
  pairingCode: string
  expiresAt: string
}

export function pairWarehouseDevice(deviceName: string): Promise<DeviceInvitation> {
  return request({ method: 'POST', url: '/auth/devices', data: { deviceName } })
}
