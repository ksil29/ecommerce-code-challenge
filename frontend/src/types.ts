export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string | null;
  categoryId: number;
  category: string;
  price: string;
  stock: number;
  reserved: number;
  available: number;
  weightKg: string | null;
  archived: boolean;
  version: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

export interface Category {
  id: number;
  name: string;
}

export interface QuoteLine {
  product: Product;
  quantity: number;
  lineTotal: string;
  available: boolean;
}

export interface Quote {
  lines: QuoteLine[];
  missingProductIds: number[];
  total: string;
  canCheckout: boolean;
}

export type OrderStatus = "PENDING_PAYMENT" | "PAID" | "PAYMENT_FAILED" | "EXPIRED";

export interface OrderLine {
  sku: string;
  productName: string;
  unitPrice: string;
  quantity: number;
  lineTotal: string;
}

export interface OrderSummary {
  id: string;
  status: OrderStatus;
  statusLabel: string;
  customerName: string;
  customerEmail: string;
  total: string;
  currency: string;
  cardLast4: string | null;
  paymentReference: string | null;
  failureReason: string | null;
  createdAt: string;
}

export interface Order extends OrderSummary {
  lines: OrderLine[];
}

export interface Me {
  authenticated: boolean;
  username: string | null;
  admin: boolean;
  fullName: string | null;
  email: string | null;
}

export interface AuditEvent {
  id: number;
  occurredAt: string;
  actor: string;
  action: string;
  entityType: string;
  entityId: string | null;
  details: string;
}

export type IssueKind = "REJECTED" | "CONFLICT" | "DUPLICATE" | "WARNING";

export interface ImportIssue {
  line: number;
  sku: string | null;
  kind: IssueKind;
  messages: string[];
}

export interface ImportReport {
  fileName: string;
  rowsRead: number;
  blankRows: number;
  created: number;
  updated: number;
  unchanged: number;
  rejected: number;
  conflicts: number;
  duplicates: number;
  warnings: number;
  issues: ImportIssue[];
}

export interface FieldError {
  field: string;
  message: string;
}

export interface ProductInput {
  sku: string;
  name: string;
  description: string;
  category: string;
  price: string;
  stock: string;
  weightKg: string;
}

export interface OrderInput {
  items: { productId: number; quantity: number }[];
  expectedTotal: string;
  customerName: string;
  customerEmail: string;
  cardNumber: string;
}

export interface Settings {
  maxQuantity: number;
  paymentTimeoutSeconds: number;
  reconcileIntervalSeconds: number;
  passwordMinLength: number;
  passwordMaxLength: number;
  apiDocs: boolean;
}
