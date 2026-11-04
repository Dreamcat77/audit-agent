import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { ApiError, setUnauthenticatedHandler } from "../api/client";
import { userFacingError } from "../api/userFacingError";
import {
  getCurrentUser,
  login as loginRequest,
  logout as logoutRequest,
  type AuthenticatedUser,
} from "../api/auth";
import { LoginPage } from "./LoginPage";

const DEMO_USER: AuthenticatedUser = {
  userId: "demo",
  username: "demo",
  realName: "本地用户",
  roles: ["AUDITOR"],
};

export const demoAuthEnabled =
  import.meta.env.DEV && import.meta.env.VITE_AUTH_MODE === "demo";

interface AuthContextValue {
  user: AuthenticatedUser | null;
  loading: boolean;
  authenticated: boolean;
  initializationError: string | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthenticatedUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [initializationError, setInitializationError] = useState<string | null>(null);

  useEffect(() => {
    if (demoAuthEnabled) {
      setUnauthenticatedHandler(null);
      return;
    }

    setUnauthenticatedHandler(() => setUser(null));
    return () => setUnauthenticatedHandler(null);
  }, []);

  const refreshUser = useCallback(async () => {
    if (demoAuthEnabled) {
      setUser(DEMO_USER);
      setInitializationError(null);
      return;
    }

    try {
      setUser(await getCurrentUser());
      setInitializationError(null);
    } catch (error) {
      setUser(null);
      if (error instanceof ApiError && error.code === "UNAUTHENTICATED") {
        setInitializationError(null);
        return;
      }
      setInitializationError(userFacingError(error, "认证服务暂时无法连接，请稍后重试。"));
      throw error;
    }
  }, []);

  useEffect(() => {
    let active = true;
    const restoreSession = async () => {
      try {
        await refreshUser();
      } catch {
        // refreshUser records the error for the login screen without retrying.
      } finally {
        if (active) setLoading(false);
      }
    };
    void restoreSession();
    return () => {
      active = false;
    };
  }, [refreshUser]);

  const login = useCallback(async (username: string, password: string) => {
    if (demoAuthEnabled) {
      setUser(DEMO_USER);
      setInitializationError(null);
      return;
    }
    setUser(await loginRequest(username, password));
    setInitializationError(null);
  }, []);

  const logout = useCallback(async () => {
    if (!demoAuthEnabled) await logoutRequest();
    setUser(null);
  }, []);

  const value = useMemo<AuthContextValue>(() => ({
    user,
    loading,
    authenticated: user !== null,
    initializationError,
    login,
    logout,
    refreshUser,
  }), [user, loading, initializationError, login, logout, refreshUser]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used within AuthProvider");
  return context;
}

export function AuthGate({ children }: { children: ReactNode }) {
  const { authenticated, initializationError, loading } = useAuth();

  if (loading) {
    return (
      <div className="h-screen w-screen flex items-center justify-center bg-slate-50 text-sm text-blue-700">
        <div className="flex items-center gap-3 rounded-lg border border-slate-200 bg-slate-50 px-5 py-4">
          <span className="size-4 rounded-full border-2 border-blue-400/30 border-t-blue-400 animate-spin" />
          正在恢复登录状态…
        </div>
      </div>
    );
  }

  return authenticated ? <>{children}</> : <LoginPage initialError={initializationError} />;
}
