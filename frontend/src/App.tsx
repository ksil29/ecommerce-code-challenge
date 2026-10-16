import { Link, Navigate, Route, Routes } from "react-router";
import { AccountLayout, AdminLayout, Layout } from "./components/Layout";
import { AdminOrdersPage } from "./pages/admin/AdminOrdersPage";
import { AdminProductsPage } from "./pages/admin/AdminProductsPage";
import { AuditPage } from "./pages/admin/AuditPage";
import { ImportPage } from "./pages/admin/ImportPage";
import { ProductFormPage } from "./pages/admin/ProductFormPage";
import { CartPage } from "./pages/CartPage";
import { CheckoutPage } from "./pages/CheckoutPage";
import { LoginPage } from "./pages/LoginPage";
import { MyOrdersPage } from "./pages/MyOrdersPage";
import { OrderPage } from "./pages/OrderPage";
import { ProductPage } from "./pages/ProductPage";
import { RegisterPage } from "./pages/RegisterPage";
import { ShopPage } from "./pages/ShopPage";
import { usePageTitle } from "./usePageTitle";

function NotFound() {
  usePageTitle("Page not found");
  return (
    <div className="text-center py-5">
      <h1 className="h3" tabIndex={-1}>
        Page not found
      </h1>
      <Link to="/">Back to the shop</Link>
    </div>
  );
}

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<ShopPage />} />
        <Route path="products/:sku" element={<ProductPage />} />
        <Route path="cart" element={<CartPage />} />
        <Route path="checkout" element={<CheckoutPage />} />
        <Route path="orders/:id" element={<OrderPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="account" element={<AccountLayout />}>
          <Route path="orders" element={<MyOrdersPage />} />
        </Route>
        <Route path="admin" element={<AdminLayout />}>
          <Route index element={<Navigate to="products" replace />} />
          <Route path="products" element={<AdminProductsPage />} />
          <Route path="products/new" element={<ProductFormPage />} />
          <Route path="products/:id/edit" element={<ProductFormPage />} />
          <Route path="import" element={<ImportPage />} />
          <Route path="orders" element={<AdminOrdersPage />} />
          <Route path="audit" element={<AuditPage />} />
        </Route>
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}
