import { type FormEvent, useState } from "react";
import { Loader2, LockKeyhole, ShieldCheck, User } from "lucide-react";
import { ApiError } from "../api/client";
import { userFacingError } from "../api/userFacingError";
import { useAuth } from "./AuthProvider";
import "./LoginPage.css";

// 客户 Logo 接入点：将图片放入 frontend/public 后，把这里改为对应的根路径即可。
const brandLogoSrc: string | null = null;

export function LoginPage({ initialError = null }: { initialError?: string | null }) {
  const { login } = useAuth();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(initialError);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!username.trim() || !password) return;
    setLoading(true);
    setError(null);
    try {
      await login(username.trim(), password);
    } catch (loginError) {
      setError(loginError instanceof ApiError && loginError.status === 401
        ? "用户名或密码错误。"
        : userFacingError(loginError, "暂时无法登录，请稍后重试。"));
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-page" aria-label="智能审计系统登录">
      <div className="login-shell">
        <aside className="login-brand" aria-label="系统品牌区">
          <div className="login-brand__orb login-brand__orb--top" aria-hidden="true" />
          <div className="login-brand__orb login-brand__orb--bottom" aria-hidden="true" />

          <div
            className="brand-logo-slot"
            data-brand-logo-slot="true"
            aria-hidden="true"
          >
            {brandLogoSrc ? (
              <img src={brandLogoSrc} alt="" className="brand-logo-slot__image" />
            ) : null}
          </div>

          <div className="login-brand__identity">
            <p className="login-brand__name">智能审计系统</p>
            <p className="login-brand__subtitle">Intelligent Audit System</p>
            <span className="login-brand__accent" aria-hidden="true" />
          </div>

          <div className="login-brand__support-slot" aria-hidden="true" />
        </aside>

        <section className="login-card" aria-labelledby="login-title">
          <header className="login-card__header">
            <h1 id="login-title">用户登录</h1>
            <p>请输入您的账号和密码以访问系统</p>
          </header>

          <form onSubmit={handleSubmit} className="login-form">
            <label className="login-field" htmlFor="login-username">
              <span className="login-field__label">用户账号</span>
              <span className="login-field__control">
                <User size={17} aria-hidden="true" />
                <input
                  id="login-username"
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  autoComplete="username"
                  placeholder="请输入用户名"
                  disabled={loading}
                />
              </span>
            </label>

            <label className="login-field" htmlFor="login-password">
              <span className="login-field__label">登录密码</span>
              <span className="login-field__control">
                <LockKeyhole size={17} aria-hidden="true" />
                <input
                  id="login-password"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="current-password"
                  placeholder="请输入密码"
                  disabled={loading}
                />
              </span>
            </label>

            {error && (
              <p role="alert" className="login-form__error">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={loading || !username.trim() || !password}
              className="login-form__submit"
            >
              {loading && <Loader2 size={16} className="animate-spin" aria-hidden="true" />}
              {loading ? "正在登录…" : "登录"}
            </button>
          </form>

          <p className="login-card__security">
            <ShieldCheck size={15} aria-hidden="true" />
            <span>安全认证 · 会话保护</span>
          </p>
        </section>
      </div>
    </main>
  );
}
