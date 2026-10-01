# SpendCore

SpendCore is a reusable spend-control platform for business applications. It models **business-owned purchasing authority**, not customer deposit accounts.

Core concepts:

- Businesses
- Customers / authorized users
- Internal purchase budgets
- Purchase requests
- Rule-based authorization
- Ephemeral / single-purpose virtual card intents
- Transactions and refunds
- Audit trail
- Provider abstraction (Mock now; Stripe Issuing / Marqeta adapters can be added after program approval)

> Regulatory note: SpendCore is software architecture, not a legal determination. Live card issuance, money movement, prepaid access, and lending programs must be reviewed with the selected issuer/provider and qualified counsel for every jurisdiction where they operate.

## Repository layout

- `android/` — SpendCore Android admin app (Kotlin + Jetpack Compose)
- `backend/` — FastAPI service and authorization engine
- `docs/` — architecture and integration docs
- `.github/workflows/` — CI for Android and backend

## MVP flow

1. A business creates an internal purchase budget for a customer/order.
2. An external business app submits a concrete purchase request.
3. SpendCore validates amount and business rules.
4. If approved, SpendCore creates a provider card intent (Mock in MVP).
5. A real provider adapter can later issue/restrict a virtual card for that purchase.
6. Captures/refunds update the authorization ledger and audit log.

## Important terminology

SpendCore intentionally uses **budget / purchase authority / remaining authorization**, not wallet or customer balance. The ledger represents what a business authorizes from its own funding program; it must not be presented as a bank account or transferable stored value unless a licensed/approved program explicitly supports that product.
