import { useEffect, useState } from "react";
import { request } from "../../api/client";
import { userFacingError } from "../../api/userFacingError";
import { ConfirmDialog, FormDialog } from "../../app/components/ConfirmDialog";

type Person = { id: string; username: string; displayName: string; status: string; administrator: boolean };
type People = { items: Person[]; total: number; page: number; size: number };
const pageSize = 20;
const emptyForm = { username: "", displayName: "", password: "" };
const inputClass = "mt-1 h-10 w-full rounded border border-slate-300 px-3";
const statusLabels: Record<string, string> = { ACTIVE: "正常", LOCKED: "暂时锁定", DISABLED: "已停用" };

export function UserAdministrationPage({ currentUserId }: { currentUserId?: string }) {
  const [data, setData] = useState<People | null>(null);
  const [page, setPage] = useState(1);
  const [editor, setEditor] = useState<{ person?: Person } | null>(null);
  const [statusTarget, setStatusTarget] = useState<Person | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [dialogError, setDialogError] = useState("");
  const [form, setForm] = useState(emptyForm);

  const load = async (target: number) => {
    setLoading(true);
    setError("");
    try {
      setData(await request<People>("/api/v1/system/users", { params: { page: String(target), size: String(pageSize) } }));
    } catch (e) {
      setError(userFacingError(e, "人员列表加载失败"));
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void load(page); }, [page]);

  function openEditor(person?: Person) {
    setDialogError("");
    setForm(person ? { username: person.username, displayName: person.displayName, password: "" } : emptyForm);
    setEditor({ person });
  }

  async function save() {
    if (!editor || saving) return;
    setSaving(true);
    setDialogError("");
    const edited = editor.person;
    try {
      await request<Person>(edited ? userPath(edited) : "/api/v1/system/users", {
        method: edited ? "PUT" : "POST",
        body: { ...form, username: form.username.trim(), displayName: form.displayName.trim() },
      });
      setEditor(null);
      setForm(emptyForm);
      if (!edited && page !== 1) setPage(1); else await load(page);
    } catch (e) {
      setDialogError(userFacingError(e, edited ? "保存人员信息失败" : "添加人员失败"));
    } finally {
      setSaving(false);
    }
  }

  const disabling = statusTarget?.status !== "DISABLED";
  const statusAction = disabling ? "停用" : "启用";

  async function updateStatus() {
    if (!statusTarget || saving) return;
    setSaving(true);
    setDialogError("");
    try {
      await request<Person>(userPath(statusTarget) + "/status", {
        method: "PATCH",
        body: { status: disabling ? "DISABLED" : "ACTIVE" },
      });
      setStatusTarget(null);
      await load(page);
    } catch (e) {
      setDialogError(userFacingError(e, statusAction + "账号失败"));
    } finally {
      setSaving(false);
    }
  }

  const editing = Boolean(editor?.person);
  const usernameOk = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,63}$/.test(form.username.trim());
  const passwordOk = editing && !form.password || form.password.length >= 8;

  return <section className="delivery-page space-y-4">
    <header className="delivery-page-header">
      <h1 className="delivery-page-title">人员管理</h1>
      <button type="button" className="delivery-primary-action" disabled={loading} onClick={() => openEditor()}>添加人员</button>
    </header>
    {error && <p role="alert" className="text-sm text-red-700">{error}<button type="button" onClick={() => void load(page)} className="ml-3 underline">重试</button></p>}
    <div className="delivery-surface overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-slate-500"><tr>{["姓名", "用户名", "身份", "状态", "操作"].map(label => <th scope="col" className="whitespace-nowrap px-5 py-3 font-medium" key={label}>{label}</th>)}</tr></thead>
          <tbody>{data?.items.map(person => <tr key={person.id} className="border-t border-slate-100">
            <td className="px-5 py-3 [overflow-wrap:anywhere]">{person.displayName}</td>
            <td className="px-5 py-3 [overflow-wrap:anywhere]">{person.username}</td>
            <td className="whitespace-nowrap px-5 py-3">{person.administrator ? "系统管理员" : "普通用户"}</td>
            <td className="whitespace-nowrap px-5 py-3">
              <span className={person.status === "ACTIVE" ? "text-emerald-700" : "text-slate-500"}>{statusLabels[person.status] ?? "—"}</span>
            </td>
            <td className="whitespace-nowrap px-5 py-3">
              <button type="button" className="mr-4 text-blue-700 hover:underline" disabled={loading} onClick={() => openEditor(person)}>编辑</button>
              {person.id !== currentUserId && <button type="button" disabled={loading}
                className={person.status === "DISABLED" ? "text-emerald-700 hover:underline" : "text-red-700 hover:underline"}
                onClick={() => { setDialogError(""); setStatusTarget(person); }}>
                {person.status === "DISABLED" ? "启用" : "停用"}
              </button>}
            </td>
          </tr>)}</tbody>
        </table>
      </div>
      {loading && <p className="p-5 text-sm text-slate-500">正在加载…</p>}
      {!loading && data?.total === 0 && <p className="p-5 text-sm text-slate-500">暂无人员</p>}
      <footer className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 px-5 py-3 text-sm text-slate-600">
        <span>共 {data?.total ?? 0} 人</span>
        <div className="flex items-center gap-3">
          <button type="button" className="delivery-secondary-action" disabled={loading || page === 1} onClick={() => setPage(p => p - 1)}>上一页</button>
          <span>第 {page} 页</span>
          <button type="button" className="delivery-secondary-action" disabled={loading || page * pageSize >= (data?.total ?? 0)} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      </footer>
    </div>
    {editor && <FormDialog open title={editing ? "编辑人员" : "添加人员"} onOpenChange={open => {
      if (!open && !saving) { setEditor(null); setForm(emptyForm); }
    }} onSubmit={() => void save()} loading={saving} submitLabel={editing ? "保存" : "添加"}
      submitDisabled={!usernameOk || !form.displayName.trim() || !passwordOk}>
      <label className="block text-sm">姓名<input className={inputClass} value={form.displayName} maxLength={80} required onChange={e => setForm({ ...form, displayName: e.target.value })}/></label>
      <label className="block text-sm">用户名<input autoComplete="off" className={inputClass} value={form.username} minLength={2} maxLength={64} required onChange={e => setForm({ ...form, username: e.target.value })}/></label>
      <label className="block text-sm">{editing ? "新密码" : "初始密码"}
        <input type="password" autoComplete="new-password" className={inputClass}
          placeholder={editing ? "留空则保留原密码" : "至少 8 位"}
          value={form.password} minLength={8} maxLength={72} required={!editing} onChange={e => setForm({ ...form, password: e.target.value })}/>
      </label>
      {dialogError && <p role="alert" className="text-sm text-red-700">{dialogError}</p>}
    </FormDialog>}
    {statusTarget && <ConfirmDialog open title={statusAction + "账号"}
      description={disabling ? `停用后，${statusTarget.displayName} 将退出系统，无法继续登录。` : `启用后，${statusTarget.displayName} 可以重新登录。`}
      confirmLabel={statusAction} loading={saving} destructive={disabling} error={dialogError || undefined}
      onOpenChange={open => { if (!open && !saving) setStatusTarget(null); }} onConfirm={() => void updateStatus()}/>}
  </section>;
}

function userPath(person: Person) {
  return "/api/v1/system/users/" + encodeURIComponent(person.id);
}
