"use client";

import Link from "next/link";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { disablePushDevice, enableCurrentDevice, pushCapability, readPushClickDiagnostics, subscribeCapability } from "./browser";
import { pushErrorMessage } from "./errors";
import { pushDevicesQuery, pushPublicKeyQuery, refreshPush } from "./queries";
import type { PushCapability, PushClickDiagnostics } from "./browser";
import type { PushDevice } from "./types";

export function CapabilityNotice({ capability }: { capability: PushCapability }) {
  const messages: Record<PushCapability, string> = {
    CHECKING: "이 기기의 알림 지원 상태를 확인하고 있어요.",
    INSECURE: "현재 주소는 HTTP로 접속한 개발 화면이에요. 스마트폰 Push 알림은 HTTPS 주소에서 설정할 수 있어요. 예약과 내 알림함은 지금도 이용할 수 있어요.",
    INSTALL_REQUIRED: "iPhone·iPad에서는 Safari 공유 메뉴의 ‘홈 화면에 추가’로 정음을 설치한 뒤, 홈 화면의 정음 앱에서 이 화면을 다시 열어 주세요. iOS·iPadOS 16.4 이상이 필요해요.",
    UNSUPPORTED: "이 브라우저는 Push 알림을 지원하지 않아요. 지원하는 최신 브라우저를 사용하거나 내 알림함에서 확인해 주세요.",
    DENIED: "이 기기의 알림 권한이 차단되어 있어요. 브라우저 또는 휴대폰 설정에서 정음 알림을 허용한 뒤 다시 확인해 주세요. 권한 창을 반복해서 띄우지 않아요.",
    AVAILABLE: "이 기기에서 알림을 켤 수 있어요. 버튼을 누르면 필요한 경우 브라우저가 알림 허용 여부를 물어봐요.",
  };
  return <p role="status" className="rounded-xl bg-paper p-4 text-sm leading-7">{messages[capability]}</p>;
}
function dateTime(value: string) { return new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value)); }
function PushClickDiagnostic() {
  const [pending, setPending] = useState(false);
  const [diagnostics, setDiagnostics] = useState<PushClickDiagnostics | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  async function read() {
    if (pending) return;
    setPending(true); setMessage(null);
    try {
      const result = await readPushClickDiagnostics();
      setDiagnostics(result);
      if (!result) setMessage("아직 이 기기의 알림 연결 정보가 없어요.");
    } catch { setDiagnostics(null); setMessage("연결 정보를 읽지 못했어요. ‘이 기기 다시 연결’ 후 다시 확인해 주세요."); }
    finally { setPending(false); }
  }
  return <details className="mt-5 border-t border-diary-border pt-4 text-sm"><summary className="cursor-pointer font-semibold">알림 열기 문제 확인</summary><p className="mt-3 leading-7 text-navy-soft">알림을 눌러도 화면이 바뀌지 않으면 진단 확인을 눌러 아래 문구를 알려 주세요. 알림 본문과 계정 정보는 포함하지 않아요.</p><button type="button" className={`${secondaryButton} mt-3`} disabled={pending} onClick={() => void read()}>{pending ? "확인 중…" : "진단 확인"}</button>{message && <p role="status" className="mt-3 leading-7">{message}</p>}{diagnostics && <pre role="status" className="mt-3 whitespace-pre-wrap break-words rounded-xl bg-paper p-4 text-xs leading-6">{`알림 연결 버전: ${diagnostics.workerVersion}\n최근 클릭 감지: ${diagnostics.clickReceivedAt ? dateTime(diagnostics.clickReceivedAt) : "없음"}\n창 이동 결과: ${diagnostics.lastNativeResult ?? "없음"}\n앱 복구 상태: ${diagnostics.lastRecovery ?? "없음"}\n확인한 창 수: ${diagnostics.windowCount ?? "없음"}\n목적지 일치: ${diagnostics.targetMatched === null ? "미확인" : diagnostics.targetMatched ? "예" : "아니요"}`}</pre>}</details>;
}
export function PushDeviceCard({ device, pending, onRemove }: { device: PushDevice; pending: boolean; onRemove: () => void }) {
  return <li className="min-w-0 rounded-2xl border border-diary-border bg-white p-5"><div className="flex flex-wrap items-center gap-2"><h3 className="font-bold break-words">{device.deviceName}</h3>{device.currentDevice && <span className="rounded-lg bg-yellow/15 px-3 py-1 text-xs font-semibold">현재 기기</span>}<span className="rounded-lg bg-paper px-3 py-1 text-xs font-semibold">{device.active ? "알림 켜짐" : "알림 꺼짐"}</span></div><p className="mt-3 text-xs leading-6 text-navy-soft">마지막 연결 {dateTime(device.updatedAt)} · 서울 기준</p>{device.active && <button type="button" className={`${secondaryButton} mt-4`} disabled={pending} onClick={onRemove}>{device.currentDevice ? "이 기기 알림 끄기" : `${device.deviceName} 알림 끄기`}</button>}</li>;
}
export function PushSettings({ scope }: { scope: string }) {
  const client = useQueryClient();
  const configuration = useQuery(pushPublicKeyQuery(scope));
  const devices = useQuery(pushDevicesQuery(scope));
  const capability = useSyncExternalStore(subscribeCapability, pushCapability, () => "CHECKING" as const);
  const [name, setName] = useState("내 기기");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const current = devices.data?.subscriptions.find((device) => device.active && device.currentDevice);
  const busy = pending || devices.isFetching || configuration.isFetching;
  async function enable() {
    if (controller.current || !configuration.data?.enabled || !configuration.data.publicKey) return;
    const abort = new AbortController(); controller.current = abort; setPending(true); setError(null); setNotice(null);
    try {
      await enableCurrentDevice(configuration.data.publicKey, name, abort.signal);
      if (abort.signal.aborted) return;
      setNotice("이 기기의 알림 연결을 확인했어요. 실제 도착은 브라우저·휴대폰 설정과 네트워크 상태에 따라 달라질 수 있어요.");
      await refreshPush(client);
    } catch (failure) { if (!abort.signal.aborted) { setError(failure); await refreshPush(client); } }
    finally { controller.current = null; if (!abort.signal.aborted) setPending(false); }
  }
  async function remove(device: PushDevice) {
    if (controller.current) return;
    const abort = new AbortController(); controller.current = abort; setPending(true); setError(null); setNotice(null);
    try {
      const result = await disablePushDevice(device, abort.signal);
      if (abort.signal.aborted) return;
      setNotice(`${device.currentDevice ? "이 기기" : device.deviceName}의 서버 알림 연결을 껐어요. 다른 기기의 설정은 유지돼요.${result.browserCleanupComplete ? "" : " 브라우저 알림 정리를 마치지 못했어요. 브라우저 설정에서도 정음 알림을 꺼 주세요."}`);
      await refreshPush(client);
    } catch (failure) { if (!abort.signal.aborted) { setError(failure); await refreshPush(client); } }
    finally { controller.current = null; if (!abort.signal.aborted) setPending(false); }
  }
  return <div className="space-y-6"><section><p className="text-sm font-semibold text-navy-soft">MY STUDIO NOTE</p><h1 className="mt-2 text-3xl font-extrabold">알림 설정</h1><p className="mt-3 text-sm leading-7 text-navy-soft">정음의 중요 공지·예약·교환 소식과 합주 시작 1시간 전 알림을 기기로 받아요. 알림을 켜지 않아도 모든 소식은 내 알림함에서 확인할 수 있어요.</p><Link href="/notifications" className={`${secondaryButton} mt-4`}>내 알림함으로</Link></section>
    <section className={panelClass}><h2 className="text-xl font-bold">이 기기 알림</h2><div className="mt-4"><CapabilityNotice capability={capability} /></div>
      {configuration.isPending && <p role="status" className="mt-4 text-sm text-navy-soft">서버의 알림 설정을 확인하고 있어요.</p>}
      {configuration.error && <div className="mt-4"><FormError message={pushErrorMessage(configuration.error)} /></div>}
      {configuration.data && !configuration.error && !configuration.data.enabled && <p role="status" className="mt-4 rounded-xl bg-paper p-4 text-sm leading-7">현재 서버의 Push 발송은 꺼져 있어요. 내 알림함은 계속 이용할 수 있어요. 서버 설정이 준비되면 여기서 알림을 켤 수 있어요.</p>}
      <label htmlFor="push-device-name" className="mt-5 block text-sm font-semibold">알아보기 쉬운 기기 이름</label><input id="push-device-name" className={`${inputClass} mt-2 max-w-md`} value={name} maxLength={80} autoComplete="off" placeholder="예: 내 휴대폰" disabled={pending} onChange={(event) => setName(event.target.value)} />
      <div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={busy || capability !== "AVAILABLE" || !configuration.data?.enabled || Boolean(configuration.error) || Boolean(devices.error)} onClick={() => void enable()}>{pending ? "설정 확인 중…" : current ? "이 기기 다시 연결" : "이 기기 알림 켜기"}</button><button type="button" className={secondaryButton} disabled={busy} onClick={() => void refreshPush(client)}>설정·기기 새로 확인</button></div>
      <div className="mt-4"><FormError message={error ? pushErrorMessage(error) : null} /></div>{notice && <p role="status" className="mt-4 rounded-xl bg-paper p-4 text-sm leading-7">{notice}</p>}
      <p className="mt-4 text-sm leading-7 text-navy-soft">로그아웃하면 이 기기의 알림만 중단돼요. 다른 기기는 그대로 유지돼요. 다시 로그인한 뒤 알림 켜기를 눌러 연결해 주세요.</p>
      <PushClickDiagnostic />
    </section>
    <section className={panelClass}><h2 className="text-xl font-bold">내 계정의 기기</h2><p className="mt-3 text-sm leading-7 text-navy-soft">기기별로 알림을 끌 수 있어요. 다른 회원의 기기는 표시되지 않아요.</p>{devices.isPending && <p role="status" className="mt-4 text-sm">기기 목록을 불러오고 있어요.</p>}{devices.error ? <div className="mt-4"><FormError message={pushErrorMessage(devices.error)} /></div> : devices.data && (devices.data.subscriptions.length ? <ul className="mt-5 grid gap-4 sm:grid-cols-2">{devices.data.subscriptions.map((device) => <PushDeviceCard key={device.id} device={device} pending={busy} onRemove={() => void remove(device)} />)}</ul> : <p className="mt-4 text-sm leading-7 text-navy-soft">아직 연결한 기기가 없어요. 위 버튼으로 이 기기에서 알림을 켜 주세요.</p>)}</section>
    <section className={panelClass}><h2 className="text-xl font-bold">휴대폰에 정음 설치하기</h2><ul className="mt-4 space-y-3 text-sm leading-7 text-navy-soft"><li>iPhone·iPad: HTTPS 주소를 Safari로 열고 공유 → 홈 화면에 추가를 선택해요. 홈 화면의 정음 앱을 열고 로그인한 뒤 알림을 켜 주세요.</li><li>Android: HTTPS 주소를 Chrome으로 열고 메뉴 → 앱 설치 또는 홈 화면에 추가를 선택해요. 메뉴 이름은 기기·브라우저에 따라 달라요.</li><li>알림을 차단했다면 브라우저·휴대폰 설정에서 정음의 알림을 허용해 주세요. 설치나 알림 허용 없이도 예약과 알림함을 이용할 수 있어요.</li></ul><p className="mt-4 text-sm leading-7 text-navy-soft">시작 1시간 전을 지난 뒤 만든 예약에는 지난 알림을 다시 보내지 않아요. 연속 60분 예약 두 건은 각 블록 기준으로 안내해요. 네트워크·OS 상태에 따라 실제 알림이 늦게 도착할 수 있어요.</p></section>
  </div>;
}
