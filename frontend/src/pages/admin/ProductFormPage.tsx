import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, ApiError, errorMessage } from "../../api";
import type { Category, Product, ProductInput } from "../../types";
import { useErrorFocus } from "../../useErrorFocus";
import { useLoad } from "../../useLoad";
import { usePageTitle } from "../../usePageTitle";

const EMPTY: ProductInput = { sku: "", name: "", description: "", category: "", price: "", stock: "", weightKg: "" };

interface Field {
  name: keyof ProductInput;
  label: string;
  hint?: string;
}

const FIELDS: Field[] = [
  { name: "sku", label: "SKU", hint: "Letters, digits and single hyphens; stored in upper case" },
  { name: "name", label: "Name" },
  { name: "category", label: "Category", hint: "Pick an existing one or type a new one" },
  { name: "price", label: "Price (USD)", hint: "A plain decimal such as 19.99" },
  { name: "stock", label: "Stock on hand" },
  { name: "weightKg", label: "Weight (kg)", hint: "Optional" },
];

const FIELD_NAMES = [...FIELDS.map(({ name }) => name), "description"];

function toInput(product: Product): ProductInput {
  return {
    sku: product.sku,
    name: product.name,
    description: product.description ?? "",
    category: product.category,
    price: product.price,
    stock: String(product.stock),
    weightKg: product.weightKg ?? "",
  };
}

interface EditorProps {
  product: Product | null;
  categories: Category[];
  onReload: () => void;
}

function ProductEditor({ product, categories, onReload }: EditorProps) {
  const navigate = useNavigate();
  const [form, setForm] = useState<ProductInput>(product ? toInput(product) : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string>();
  const [stale, setStale] = useState(false);
  const [saving, setSaving] = useState(false);
  const { formRef, messageRef, failed } = useErrorFocus();

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSaving(true);
    setErrors({});
    setMessage(undefined);
    try {
      const saved = product
        ? await api.admin.updateProduct(product.id, form, product.version)
        : await api.admin.createProduct(form);
      navigate("/admin/products", { state: { notice: `Saved ${saved.sku}` } });
    } catch (error) {
      setErrors(error instanceof ApiError ? error.fieldErrors() : {});
      setMessage(errorMessage(error, FIELD_NAMES));
      setStale(error instanceof ApiError && error.code === "STALE_VERSION");
      failed();
    } finally {
      setSaving(false);
    }
  };

  const describedBy = (name: string, hint: string | undefined) => {
    if (errors[name]) return `${name}-error`;
    return hint ? `${name}-hint` : undefined;
  };

  const field = ({ name, label, hint }: Field) => (
    <div className="mb-3" key={name}>
      <label className="form-label" htmlFor={name}>
        {label}
      </label>
      <input
        id={name}
        className={`form-control ${errors[name] ? "is-invalid" : ""}`}
        value={form[name]}
        list={name === "category" ? "categories" : undefined}
        onChange={(event) => setForm({ ...form, [name]: event.target.value })}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={describedBy(name, hint)}
      />
      {errors[name] ? (
        <div id={`${name}-error`} className="invalid-feedback">
          {errors[name]}
        </div>
      ) : (
        hint && (
          <div id={`${name}-hint`} className="form-text">
            {hint}
          </div>
        )
      )}
    </div>
  );

  return (
    <div className="row">
      <div className="col-lg-7">
        <h1 className="h4 mb-3" tabIndex={-1}>
          {product ? `Edit ${product.sku}` : "New product"}
        </h1>
        {message && (
          <div ref={messageRef} tabIndex={-1} className="alert alert-warning" role="alert">
            {message}
            {stale && (
              <button type="button" className="btn btn-link btn-sm" onClick={onReload}>
                Reload the current values
              </button>
            )}
          </div>
        )}
        <form ref={formRef} onSubmit={(event) => void submit(event)} noValidate>
          {FIELDS.map((definition) =>
            definition.name === "stock" && product ? (
              <div className="row" key={definition.name}>
                <div className="col-sm-6">{field(definition)}</div>
                <div className="col-sm-6 mb-3">
                  <label className="form-label" htmlFor="reserved">
                    Reserved
                  </label>
                  <input id="reserved" className="form-control" value={product.reserved} readOnly aria-describedby="reserved-hint" />
                  <div id="reserved-hint" className="form-text">
                    Held by orders waiting for payment
                  </div>
                </div>
              </div>
            ) : (
              field(definition)
            ),
          )}
          <datalist id="categories">
            {categories.map((category) => (
              <option key={category.id} value={category.name} />
            ))}
          </datalist>
          <div className="mb-3">
            <label className="form-label" htmlFor="description">
              Description
            </label>
            <textarea
              id="description"
              rows={3}
              className={`form-control ${errors.description ? "is-invalid" : ""}`}
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
              aria-invalid={errors.description ? true : undefined}
              aria-describedby={describedBy("description", undefined)}
            />
            {errors.description && (
              <div id="description-error" className="invalid-feedback">
                {errors.description}
              </div>
            )}
          </div>
          <div className="d-flex gap-2">
            <button type="submit" className="btn btn-primary" disabled={saving}>
              Save
            </button>
            <Link className="btn btn-outline-secondary" to="/admin/products">
              Cancel
            </Link>
          </div>
        </form>
      </div>
    </div>
  );
}

export function ProductFormPage() {
  const { id } = useParams();
  const productId = id === undefined ? undefined : Number(id);
  const categories = useLoad("categories", () => api.categories());
  const existing = useLoad(String(productId ?? "new"), () =>
    productId === undefined ? Promise.resolve(null) : api.admin.product(productId),
  );
  const product = existing.data && existing.data.id === productId ? existing.data : null;
  usePageTitle(`${productId === undefined ? "New product" : `Edit ${product?.sku ?? "product"}`} · Admin`);

  if (existing.error) {
    return (
      <>
        <h1 className="h4 mb-3" tabIndex={-1}>
          Edit product
        </h1>
        <div className="alert alert-danger" role="alert">
          {existing.error}
        </div>
      </>
    );
  }
  if (productId !== undefined && !product) return <p className="text-body-secondary">Loading…</p>;

  return (
    <ProductEditor
      key={product ? `${product.id}:${product.version}` : "new"}
      product={product}
      categories={categories.data ?? []}
      onReload={existing.reload}
    />
  );
}
