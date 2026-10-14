export interface PushPublicKey { enabled: boolean; publicKey: string | null }
export interface PushDevice {
  id: string;
  deviceName: string;
  active: boolean;
  currentDevice: boolean;
  bindingVersion: string;
  createdAt: string;
  updatedAt: string;
}
export interface PushDeviceList { subscriptions: PushDevice[] }
export interface PushRegistration {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  deviceName: string;
}
export interface PushBinding { subscriptionId: string; bindingVersion: string }
