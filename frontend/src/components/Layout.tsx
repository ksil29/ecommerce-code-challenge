import { useEffect, useRef, useState, type RefObject } from "react";
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate } from "react-router";
import { errorMessage } from "../api";
import { signInPath, useAuth } from "../auth";
import { useCart } from "../cart";
import { useSettings } from "../settings";
import { usePageTitle } from "../usePageTitle";

function useHeadingFocus(main: RefObject<HTMLElement | null>) {
  const { pathname } = useLocation();
  const shown = useRef(pathname);

  useEffect(() => {
    if (shown.current === pathname) return;
    shown.current = pathname;
    const container = main.current;
    if (!container) return;
    const focusHeading = () => {
      const heading = container.querySelector<HTMLElement>("h1");
      heading?.focus();
      return heading !== null;
    };
    if (focusHeading()) return;
    const observer = new MutationObserver(() => {
      if (focusHeading()) observer.disconnect();
    });
    observer.observe(container, { childList: true, subtree: true });
    return () => observer.disconnect();
  }, [main, pathname]);
}

export function Layout() {
  const { count } = useCart();
  const { me, logout } = useAuth();
  const { apiDocs } = useSettings();
  const navigate = useNavigate();
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  const [logoutError, setLogoutError] = useState<string>();
  const [menuOpenAt, setMenuOpenAt] = useState<string>();
  const menuOpen = menuOpenAt === location.key;
  useHeadingFocus(main);

  const signOut = async () => {
    setLogoutError(undefined);
    try {
      await logout();
      navigate("/");
    } catch (error) {
      setLogoutError(errorMessage(error));
    }
  };

  return (
    <>
      <nav className="navbar navbar-expand-md navbar-dark shop-navbar mb-4" aria-label="Main">
        <div className="container">
          <Link className="navbar-brand d-flex align-items-center" to="/">
            <span className="navbar-brand-mark">S</span>
            Shop
          </Link>
          <button
            type="button"
            className="navbar-toggler"
            aria-controls="main-menu"
            aria-expanded={menuOpen}
            aria-label="Menu"
            onClick={() => setMenuOpenAt(menuOpen ? undefined : location.key)}
          >
            <span className="navbar-toggler-icon" />
          </button>
          <div id="main-menu" className={`collapse navbar-collapse ${menuOpen ? "show" : ""}`}>
            <div className="navbar-nav me-auto">
              <NavLink className="nav-link" to="/" end>
                Products
              </NavLink>
              <NavLink className="nav-link" to="/cart">
                Cart {count > 0 && <span className="badge rounded-pill text-bg-light">{count}</span>}
              </NavLink>
              {me?.authenticated && (
                <NavLink className="nav-link" to="/account/orders">
                  My orders
                </NavLink>
              )}
              {me?.admin && (
                <NavLink className="nav-link" to="/admin">
                  Admin
                </NavLink>
              )}
            </div>
            <div className="navbar-nav align-items-md-center">
              {me?.authenticated ? (
                <>
                  <span className="navbar-text me-2">{me.fullName ?? me.username}</span>
                  <button type="button" className="btn btn-link nav-link text-start" onClick={() => void signOut()}>
                    Log out
                  </button>
                </>
              ) : (
                <>
                  <NavLink className="nav-link" to="/login">
                    Sign in
                  </NavLink>
                  <NavLink className="nav-link" to="/register">
                    Create account
                  </NavLink>
                </>
              )}
            </div>
          </div>
        </div>
      </nav>
      <main ref={main} className="container pb-5 flex-grow-1">
        {logoutError && (
          <div className="alert alert-danger" role="alert">
            Could not log out: {logoutError}
          </div>
        )}
        <Outlet />
      </main>
      <footer className="shop-footer">
        <div className="container d-flex flex-wrap justify-content-between gap-2">
          <span>Demo shop for a code challenge. Payments are simulated.</span>
          {apiDocs && (
            <span>
              <a href="/swagger-ui.html">API documentation</a>
            </span>
          )}
        </div>
      </footer>
    </>
  );
}

function AdminsOnly() {
  usePageTitle("Admin");
  return (
    <>
      <h1 className="h4 mb-3" tabIndex={-1}>
        Admin
      </h1>
      <div className="alert alert-warning">This area is for administrators.</div>
    </>
  );
}

export function AdminLayout() {
  const { me } = useAuth();
  const { pathname, search } = useLocation();

  if (me === null) return <p className="text-body-secondary">Checking your session…</p>;
  if (!me.authenticated) return <Navigate to={signInPath(pathname, search)} replace />;
  if (!me.admin) return <AdminsOnly />;

  return (
    <>
      <ul className="nav nav-tabs mb-4">
        {[
          ["/admin/products", "Products"],
          ["/admin/import", "Import CSV"],
          ["/admin/orders", "Orders"],
          ["/admin/audit", "Audit log"],
        ].map(([to, label]) => (
          <li className="nav-item" key={to}>
            <NavLink className="nav-link" to={to ?? "/admin"}>
              {label}
            </NavLink>
          </li>
        ))}
      </ul>
      <Outlet />
    </>
  );
}

export function AccountLayout() {
  const { me } = useAuth();
  const { pathname, search } = useLocation();

  if (me === null) return <p className="text-body-secondary">Checking your session…</p>;
  if (!me.authenticated) return <Navigate to={signInPath(pathname, search)} replace />;
  return <Outlet />;
}
