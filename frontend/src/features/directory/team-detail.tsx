"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { Confirmation, QueryState, panelClass, teamKindLabel } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { addTeamMember, createSong, deleteSong, removeTeamMember, setTeamLeader, updateSong, updateTeam } from "./api";
import { refreshDirectory, rosterQuery } from "./queries";
import type { Song, SongInput, TeamDetail, TeamMember } from "./types";
import { nameError, songError } from "./validation";
import { disbandBlock } from "@/features/lifecycle/input";
import type { Team } from "./types";

function TeamAdministration({ detail, blocked }: { detail: TeamDetail; blocked: boolean }) {
  const { member } = useSession(); const client = useQueryClient(); const action = useTransientAction();
  const pending = action.pending || blocked;
  const roster = useQuery(rosterQuery(member!.id));
  const [name, setName] = useState(detail.team.name); const [rosterId, setRosterId] = useState("");
  const [validation, setValidation] = useState<string | null>(null); const [notice, setNotice] = useState<string | null>(null);
  const [removal, setRemoval] = useState<TeamMember | null>(null);
  const [leader, setLeader] = useState<{ rosterEntryId: string | null; name: string } | null>(null);
  const available = (roster.data ?? []).filter((entry) => (!entry.userId || entry.accountStatus === "ACTIVE")
    && !detail.members.some((membership) => membership.rosterEntryId === entry.id));
  async function change(task: (signal: AbortSignal) => Promise<unknown>, message: string) {
    setValidation(null); setNotice(null);
    const done = await action.run(async (signal) => { await task(signal); return true; });
    if (done) { setNotice(message); await refreshDirectory(client); }
    return done;
  }
  async function rename(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const error = nameError(name, 80, "팀 이름"); if (error) { setValidation(error); return; }
    await change((signal) => updateTeam(detail.team, name.trim(), signal), "팀 이름을 수정했어요.");
  }
  async function add(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!rosterId) { setValidation("등록할 명단을 선택해 주세요."); return; }
    if (await change((signal) => addTeamMember(detail.team.id, rosterId, signal), "팀원을 등록했어요. 가입 전 명단도 가입 후 동일한 팀 소속을 유지해요.")) setRosterId("");
  }
  async function remove() {
    if (!removal) return;
    if (await change((signal) => removeTeamMember(detail.team.id, removal.rosterEntryId, signal), "팀원을 제외했어요. 다른 팀 소속은 유지돼요.")) setRemoval(null);
  }
  async function assignLeader() {
    if (!leader) return;
    if (await change((signal) => setTeamLeader(detail.team.id, leader.rosterEntryId, signal), leader.rosterEntryId ? "이 팀의 팀장을 지정했어요." : "팀장 지정을 해제했어요.")) setLeader(null);
  }
  return <section className={panelClass} aria-labelledby="team-admin-title"><h2 id="team-admin-title" className="text-xl font-bold">팀 구성 관리</h2><p className="mt-3 text-sm leading-7 text-navy-soft">전체 관리자만 팀원과 팀장을 배정할 수 있어요. 팀장은 이 팀의 곡 목록을 관리해요.</p>
    <form className="mt-5 space-y-4" onSubmit={(event) => void rename(event)}><Field id="edit-team-name" label="팀 이름"><input id="edit-team-name" className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={pending} /></Field><button type="submit" className={secondaryButton} disabled={pending}>팀 이름 저장</button></form>
    <div className="mt-7"><QueryState pending={roster.isPending} error={roster.error} retry={() => void roster.refetch()} />{!roster.error && roster.data && <form className="space-y-4" onSubmit={(event) => void add(event)}><Field id="add-team-roster" label="팀원 명단" hint="동명이인은 명단 번호로 구분해요. 가입 전에도 등록할 수 있어요."><select id="add-team-roster" className={inputClass} value={rosterId} onChange={(event) => setRosterId(event.target.value)} disabled={pending || !available.length} aria-describedby="add-team-roster-hint"><option value="">등록할 명단 선택</option>{available.map((entry) => <option key={entry.id} value={entry.id}>{entry.name} (명단 #{entry.id}) · {entry.userId ? "계정 연결됨" : "가입 전"}</option>)}</select></Field><button type="submit" className={primaryButton} disabled={pending || !rosterId}>팀원 등록</button></form>}</div>
    <div className="mt-5"><FormError message={validation ?? action.error} />{notice && <p role="status" className="mt-3 text-sm leading-7">{notice}</p>}</div>
    <ul className="mt-5 space-y-3">{detail.members.map((item) => <li key={item.rosterEntryId} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-paper p-4"><div className="min-w-0"><span className="font-semibold break-words">{item.name}</span><p className="mt-1 text-xs text-navy-soft">명단 #{item.rosterEntryId} · {item.role === "LEADER" ? "팀장" : "팀원"} · {item.userId ? "계정 연결됨" : "가입 전"}</p></div><div className="flex flex-wrap gap-2"><button type="button" className={secondaryButton} disabled={pending} onClick={() => { setRemoval(null); setLeader({ rosterEntryId: item.role === "LEADER" ? null : item.rosterEntryId, name: item.name }); }}>{item.role === "LEADER" ? "팀장 해제" : "팀장 지정"}</button><button type="button" className={secondaryButton} disabled={pending} onClick={() => { setLeader(null); setRemoval(item); }}>팀원 제외</button></div></li>)}</ul>
    <div className="mt-5">{removal && <Confirmation title={`${removal.name}님을 이 팀에서 제외할까요?`} description={removal.role === "LEADER" ? "이 팀의 팀장 권한도 해제돼요. 후임 팀장은 관리자가 직접 지정해 주세요. 계정과 다른 팀 소속은 유지돼요." : "계정과 다른 팀 소속은 유지돼요."} pending={pending} onConfirm={() => void remove()} onCancel={() => setRemoval(null)} confirmLabel="팀원 제외" />}{leader && <Confirmation title={leader.rosterEntryId ? `${leader.name}님을 이 팀의 팀장으로 지정할까요?` : `${leader.name}님의 이 팀 팀장 지정을 해제할까요?`} description={leader.rosterEntryId ? "기존 팀장 지정은 해제돼요. 가입 전 명단이라면 가입·계정 연결 후 이 팀의 곡 관리 권한을 사용할 수 있어요. 다른 팀에는 영향을 주지 않아요." : "다른 팀의 역할과 전체 관리자 권한은 유지돼요."} pending={pending} onConfirm={() => void assignLeader()} onCancel={() => setLeader(null)} confirmLabel="팀장 지정 변경" />}</div>
  </section>;
}

function SongForm({ initial, pending, onSave, onCancel }: { initial?: Song; pending: boolean; onSave: (input: SongInput, existing?: Song) => Promise<boolean>; onCancel?: () => void }) {
  const [title, setTitle] = useState(initial?.title ?? ""); const [artist, setArtist] = useState(initial?.artist ?? "");
  const [memo, setMemo] = useState(initial?.memo ?? ""); const [order, setOrder] = useState(initial ? String(initial.sortOrder) : "");
  const [validation, setValidation] = useState<string | null>(null);
  async function save(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const error = songError(title, artist, memo, order);
    if (error) { setValidation(error); return; } setValidation(null);
    const done = await onSave({ title: title.trim(), artist: artist.trim() || undefined, memo: memo.trim() || undefined, sortOrder: order === "" ? undefined : Number(order) }, initial);
    if (done && !initial) { setTitle(""); setArtist(""); setMemo(""); setOrder(""); }
  }
  const prefix = initial ? `edit-song-${initial.id}` : "new-song";
  return <form className="mt-5 grid gap-4 rounded-2xl bg-paper p-5 sm:grid-cols-2" onSubmit={(event) => void save(event)} aria-busy={pending}><Field id={`${prefix}-title`} label="곡 제목"><input id={`${prefix}-title`} className={inputClass} value={title} onChange={(event) => setTitle(event.target.value)} required disabled={pending} /></Field><Field id={`${prefix}-artist`} label="아티스트 (선택)"><input id={`${prefix}-artist`} className={inputClass} value={artist} onChange={(event) => setArtist(event.target.value)} disabled={pending} /></Field><div className="sm:col-span-2"><Field id={`${prefix}-memo`} label="메모 (선택)"><textarea id={`${prefix}-memo`} rows={3} className={inputClass} value={memo} onChange={(event) => setMemo(event.target.value)} disabled={pending} /></Field></div><Field id={`${prefix}-order`} label="목록 순서" hint={initial ? "0 이상의 정수. 작은 번호부터 표시돼요." : "비워 두면 목록 마지막에 추가돼요."}><input id={`${prefix}-order`} type="number" min={0} max={2147483647} step={1} required={Boolean(initial)} className={inputClass} value={order} onChange={(event) => setOrder(event.target.value)} disabled={pending} aria-describedby={`${prefix}-order-hint`} /></Field><div className="sm:col-span-2"><FormError message={validation} /><div className="mt-4 flex flex-wrap gap-3"><button type="submit" className={primaryButton} disabled={pending}>{pending ? "처리하는 중…" : initial ? "곡 수정 저장" : "곡 등록"}</button>{onCancel && <button type="button" className={secondaryButton} disabled={pending} onClick={onCancel}>수정 취소</button>}</div></div></form>;
}

function SongManager({ detail, blocked }: { detail: TeamDetail; blocked: boolean }) {
  const client = useQueryClient(); const action = useTransientAction();
  const pending = action.pending || blocked;
  const [editing, setEditing] = useState<Song | null>(null); const [removal, setRemoval] = useState<Song | null>(null); const [notice, setNotice] = useState<string | null>(null);
  async function save(input: SongInput, existing?: Song) {
    setNotice(null);
    const saved = await action.run((signal) => existing ? updateSong(existing, { ...input, sortOrder: input.sortOrder ?? existing.sortOrder }, signal) : createSong(detail.team.id, input, signal));
    if (!saved) return false;
    setEditing(null); setNotice(existing ? "곡을 수정했어요." : "곡을 등록했어요."); await refreshDirectory(client); return true;
  }
  async function remove() {
    if (!removal) return;
    setNotice(null);
    const done = await action.run(async (signal) => { await deleteSong(removal, signal); return true; });
    if (done) { setRemoval(null); setNotice("곡을 삭제했어요."); await refreshDirectory(client); }
  }
  return <><h3 className="mt-6 font-bold">곡 등록</h3><SongForm pending={pending} onSave={save} /><div className="mt-4"><FormError message={action.error} />{notice && <p role="status" className="mt-3 text-sm">{notice}</p>}</div>
    {editing && <section className="mt-6" aria-label="곡 수정"><h3 className="font-bold">곡 수정</h3><SongForm key={`${editing.id}:${editing.version}`} initial={editing} pending={pending} onSave={save} onCancel={() => setEditing(null)} /></section>}
    {removal && <div className="mt-6"><Confirmation title={`“${removal.title}”을 삭제할까요?`} description="이 팀의 곡 목록에서 삭제돼요. 다른 팀의 곡은 유지돼요." pending={pending} onConfirm={() => void remove()} onCancel={() => setRemoval(null)} confirmLabel="곡 삭제" /></div>}
    <SongList songs={detail.songs} actions={(song) => <div className="mt-4 flex flex-wrap gap-2"><button type="button" className={secondaryButton} disabled={pending} onClick={() => { setRemoval(null); setEditing(song); }}>곡 수정</button><button type="button" className={secondaryButton} disabled={pending} onClick={() => { setEditing(null); setRemoval(song); }}>곡 삭제</button></div>} /></>;
}

export function SongList({ songs, actions }: { songs: Song[]; actions?: (song: Song) => React.ReactNode }) {
  return songs.length ? <ol className="mt-5 space-y-3">{songs.map((song) => <li key={song.id} className="rounded-2xl border border-navy/15 p-5"><p className="text-xs text-navy-soft">순서 {song.sortOrder}</p><h3 className="mt-2 text-lg font-bold break-words">{song.title}</h3>{song.artist && <p className="mt-2 text-sm break-words">{song.artist}</p>}{song.memo && <p className="mt-3 text-sm leading-7 whitespace-pre-wrap break-words text-navy-soft">{song.memo}</p>}{actions?.(song)}</li>)}</ol> : <p className="mt-5 text-sm text-navy-soft">아직 등록된 곡이 없어요.</p>;
}

export function TeamDetailView({ detail, readError = false, lifecycle }: {
  detail: TeamDetail; readError?: boolean;
  lifecycle?: { open: boolean; notice: string | null; onStart: (team: Team) => void };
}) {
  const { member } = useSession();
  const admin = member?.globalRole === "GLOBAL_ADMIN";
  const block = disbandBlock(detail.team);
  const blocked = Boolean(lifecycle?.open) || readError;
  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">{teamKindLabel(detail.team.kind)}</p><h1 className="mt-2 text-3xl font-extrabold break-words">{detail.team.name}</h1></section>
    <section className={panelClass} aria-labelledby="team-members-title"><h2 id="team-members-title" className="text-xl font-bold">팀원</h2>{detail.canViewMembers ? (detail.members.length ? <ul className="mt-5 grid gap-3 sm:grid-cols-2">{detail.members.map((item) => <li key={item.rosterEntryId} className="rounded-2xl bg-paper p-4"><span className="font-semibold break-words">{item.name}</span><p className="mt-2 text-xs text-navy-soft">{item.role === "LEADER" ? "팀장" : "팀원"} · {item.userId ? "계정 연결됨" : "가입 전"}</p></li>)}</ul> : <p className="mt-4 text-sm text-navy-soft">아직 등록된 팀원이 없어요.</p>) : <p className="mt-4 text-sm leading-7 text-navy-soft">팀원 정보는 해당 팀 소속 회원과 전체 관리자만 볼 수 있어요.</p>}</section>
    {detail.canManageTeam && detail.team.status === "ACTIVE" && <TeamAdministration key={`${detail.team.id}:${detail.team.version}`} detail={detail} blocked={blocked} />}
    <section className={panelClass} aria-labelledby="team-songs-title"><h2 id="team-songs-title" className="text-xl font-bold">팀별 곡 목록</h2>{detail.canManageSongs && detail.team.status === "ACTIVE" ? <SongManager detail={detail} blocked={blocked} /> : <><p className="mt-3 text-sm leading-7 text-navy-soft">{detail.team.status === "DISBANDED" ? "해체된 팀의 곡 목록은 조회만 할 수 있어요." : "곡 등록·수정·삭제는 전체 관리자와 이 팀의 팀장이 처리해요."}</p><SongList songs={detail.songs} /></>}</section>
    {admin && <section className={panelClass} aria-labelledby="team-disband-title"><h2 id="team-disband-title" className="text-xl font-bold">팀 해체</h2><p className="mt-3 text-sm leading-7 text-navy-soft">팀의 미래·진행 중 예약과 관련 교환·예정 알림을 정리하고 반복 배정을 종료해요. 완료된 이용 이력과 다른 팀·개인 예약은 유지해요.</p>{block && <p id="team-disband-block" className="mt-3 text-sm text-navy-soft">{block}</p>}<button type="button" className={`${secondaryButton} mt-4`} disabled={!lifecycle || blocked || Boolean(block)} aria-describedby={block ? "team-disband-block" : undefined} onClick={() => lifecycle?.onStart(detail.team)}>팀 해체 확인</button>{lifecycle?.notice && <p role="status" className="mt-4 text-sm leading-7">{lifecycle.notice}</p>}</section>}
  </div>;
}
