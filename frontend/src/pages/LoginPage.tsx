import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from "react-router";
import { ApiError, errorMessage } from "../api";
import { useAuth } from "../auth";
import type { Me } from "../types";
import { useErrorFocus } from "../useErrorFocus";
import { usePageTitle } from "../usePageTitle";

export function safeNext(next: string | null): string | null {
  if (!next?.startsWith("/")) return null;
  try {
    const url = new URL(next, window.location.origin);
    return url.origin === window.location.origin ? `${url.pathname}${url.search}${url.hash}` : null;
  } catch {
    return null;
  }
}

export function landingFor(me: Me, next: string | null): string {
  if (next && (!next.startsWith("/admin") || me.admin)) return next;
  return me.admin ? "/admin/products" : "/";
}

interface Failure {
  text: string;
  wrongCredentials: boolean;
}

export function LoginPage() {
  const { me, login } = useAuth();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<Failure>();
  const [busy, setBusy] = useState(false);
  const { formRef, messageRef, failed } = useErrorFocus();
  const next = safeNext(params.get("next"));
  const notice = (location.state as { notice?: string } | null)?.notice;
  usePageTitle("Sign in");

  if (me?.authenticated) return <Navigate to={landingFor(me, next)} replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const signedIn = await login(username, password);
      navigate(landingFor(signedIn, next), { replace: true });
    } catch (failure) {
      const wrongCredentials = failure instanceof ApiError && failure.status === 401;
      setError({
        text: wrongCredentials
          ? "Wrong email or password. After 5 failed attempts the account is locked for 15 minutes."
          : errorMessage(failure),
        wrongCredentials,
      });
      failed();
    } finally {
      setBusy(false);
    }
  };

  const invalid = error?.wrongCredentials === true;

  return (
    <div className="row justify-content-center">
      <div className="col-sm-9 col-md-6 col-lg-5">
        <div className="surface auth-card">
          <h1 className="h4 mb-3" tabIndex={-1}>
            Sign in
          </h1>
          {notice && !error && (
            <div className="alert alert-warning" role="alert">
              {notice}
            </div>
          )}
          {error && (
            <div id="login-error" ref={messageRef} tabIndex={-1} className="alert alert-danger" role="alert">
              {error.text}
            </div>
          )}
          <form ref={formRef} onSubmit={(event) => void submit(event)}>
            <div className="mb-3">
              <label className="form-label" htmlFor="username">
                Email (or admin username)
              </label>
              <input
                id="username"
                className={`form-control ${invalid ? "is-invalid" : ""}`}
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                aria-invalid={invalid || undefined}
                aria-describedby={invalid ? "login-error" : undefined}
                required
              />
            </div>
            <div className="mb-3">
              <label className="form-label" htmlFor="password">
                Password
              </label>
              <input
                id="password"
                type="password"
                className={`form-control ${invalid ? "is-invalid" : ""}`}
                autoComplete="current-password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                aria-invalid={invalid || undefined}
                aria-describedby={invalid ? "login-error" : undefined}
                required
              />
            </div>
            <button type="submit" className="btn btn-primary w-100" disabled={busy}>
              Sign in
            </button>
          </form>
          <p className="small text-body-secondary mt-3 mb-0">
            New here? <Link to={next ? `/register?next=${encodeURIComponent(next)}` : "/register"}>Create an account</Link>. You can also buy as a guest.
          </p>
        </div>
      </div>
    </div>
  );
}
