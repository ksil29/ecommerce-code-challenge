import {
  createContext,
  startTransition,
  useCallback,
  useContext,
  useEffect,
  useEffectEvent,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { useLocation, useNavigate } from "react-router";
import { api, onUnauthorized, SESSION_EXPIRED } from "./api";
import type { Me } from "./types";

const ANONYMOUS: Me = { authenticated: false, username: null, admin: false, fullName: null, email: null };

interface AuthContextValue {
  me: Me | null;
  login: (username: string, password: string) => Promise<Me>;
  register: (email: string, fullName: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function signInPath(pathname: string, search: string): string {
  return `/login?next=${encodeURIComponent(`${pathname}${search}`)}`;
}

async function currentUser(): Promise<Me> {
  try {
    return await api.me();
  } catch {
    return ANONYMOUS;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const navigate = useNavigate();
  const { pathname, search } = useLocation();

  useEffect(() => {
    let active = true;
    currentUser().then((current) => {
      if (active) setMe(current);
    });
    return () => {
      active = false;
    };
  }, []);

  const sessionExpired = useEffectEvent(() => {
    startTransition(() => {
      setMe(ANONYMOUS);
      if (pathname !== "/login") {
        navigate(signInPath(pathname, search), { replace: true, state: { notice: SESSION_EXPIRED } });
      }
    });
  });

  useEffect(() => onUnauthorized(() => sessionExpired()), []);

  const refresh = useCallback(async (): Promise<Me> => {
    const current = await currentUser();
    setMe(current);
    return current;
  }, []);

  const value = useMemo<AuthContextValue>(() => {
    const login = async (username: string, password: string) => {
      await api.login(username, password);
      return refresh();
    };
    return {
      me,
      login,
      register: async (email, fullName, password) => {
        await api.register(email, fullName, password);
        return login(email, password);
      },
      logout: async () => {
        await api.logout();
        await refresh();
      },
    };
  }, [me, refresh]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider");
  return value;
}
