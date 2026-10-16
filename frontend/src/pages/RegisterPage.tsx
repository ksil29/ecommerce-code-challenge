import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useSearchParams } from "react-router";
import { ApiError, errorMessage } from "../api";
import { useAuth } from "../auth";
import { useSettings } from "../settings";
import { useErrorFocus } from "../useErrorFocus";
import { usePageTitle } from "../usePageTitle";
import { landingFor, safeNext } from "./LoginPage";

const FIELDS = ["email", "fullName", "password"];

export function RegisterPage() {
  const { me, register } = useAuth();
  const { passwordMinLength, passwordMaxLength } = useSettings();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [form, setForm] = useState({ email: "", fullName: "", password: "" });
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const { formRef, messageRef, failed } = useErrorFocus();
  const next = safeNext(params.get("next"));
  usePageTitle("Create an account");

  if (me?.authenticated) return <Navigate to={landingFor(me, next)} replace />;

  const length = [...form.password].length;
  const lengthOutOfRange = length > 0 && (length < passwordMinLength || length > passwordMaxLength);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setBusy(true);
    setErrors({});
    setMessage(undefined);
    try {
      const signedIn = await register(form.email, form.fullName, form.password);
      navigate(landingFor(signedIn, next), { replace: true });
    } catch (failure) {
      setErrors(failure instanceof ApiError ? failure.fieldErrors() : {});
      setMessage(errorMessage(failure, FIELDS));
      failed();
    } finally {
      setBusy(false);
    }
  };

  const invalid = (name: string) => (errors[name] ? true : undefined);
  const describedBy = (name: string) => (errors[name] ? `${name}-error` : undefined);

  return (
    <div className="row justify-content-center">
      <div className="col-sm-10 col-md-7 col-lg-5">
        <div className="surface auth-card">
          <h1 className="h4 mb-1" tabIndex={-1}>
            Create an account
          </h1>
          <p className="small text-body-secondary">Optional: an account keeps your order history and fills in checkout for you.</p>
          {message && (
            <div ref={messageRef} tabIndex={-1} className="alert alert-warning" role="alert">
              {message}
            </div>
          )}
          <form ref={formRef} onSubmit={(event) => void submit(event)} noValidate>
            <div className="mb-3">
              <label className="form-label" htmlFor="email">
                Email
              </label>
              <input
                id="email"
                type="email"
                autoComplete="email"
                className={`form-control ${errors.email ? "is-invalid" : ""}`}
                value={form.email}
                onChange={(event) => setForm({ ...form, email: event.target.value })}
                aria-invalid={invalid("email")}
                aria-describedby={describedBy("email")}
              />
              {errors.email && (
                <div id="email-error" className="invalid-feedback">
                  {errors.email}
                </div>
              )}
            </div>
            <div className="mb-3">
              <label className="form-label" htmlFor="fullName">
                Name
              </label>
              <input
                id="fullName"
                autoComplete="name"
                className={`form-control ${errors.fullName ? "is-invalid" : ""}`}
                value={form.fullName}
                onChange={(event) => setForm({ ...form, fullName: event.target.value })}
                aria-invalid={invalid("fullName")}
                aria-describedby={describedBy("fullName")}
              />
              {errors.fullName && (
                <div id="fullName-error" className="invalid-feedback">
                  {errors.fullName}
                </div>
              )}
            </div>
            <div className="mb-3">
              <label className="form-label" htmlFor="password">
                Password
              </label>
              <div className="input-group has-validation">
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="new-password"
                  className={`form-control ${errors.password ? "is-invalid" : ""}`}
                  value={form.password}
                  onChange={(event) => setForm({ ...form, password: event.target.value })}
                  aria-invalid={invalid("password")}
                  aria-describedby={errors.password ? "password-error password-hint" : "password-hint"}
                />
                <button type="button" className="btn btn-outline-secondary" onClick={() => setShowPassword(!showPassword)}>
                  {showPassword ? "Hide" : "Show"}
                </button>
                {errors.password && (
                  <div id="password-error" className="invalid-feedback">
                    {errors.password}
                  </div>
                )}
              </div>
              <div id="password-hint" className={`form-text ${lengthOutOfRange ? "text-warning-emphasis" : ""}`}>
                Use {passwordMinLength} to {passwordMaxLength} characters ({length} so far). A few unrelated words make a strong
                password; no special characters are required. Common passwords and ones that contain your email or name are refused.
              </div>
            </div>
            <button type="submit" className="btn btn-primary w-100" disabled={busy}>
              Create account
            </button>
          </form>
          <p className="small text-body-secondary mt-3 mb-0">
            Already registered? <Link to={next ? `/login?next=${encodeURIComponent(next)}` : "/login"}>Sign in</Link>
          </p>
        </div>
      </div>
    </div>
  );
}
