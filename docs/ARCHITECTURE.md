# SpendCore architecture

## Purpose

SpendCore is a reusable authorization layer between a business application and a regulated card-issuing/payment provider.

```text
Business app (Paquetería / future app)
        |
        v
   SpendCore API
        |
        +-- Budget engine
        +-- Rule engine
        +-- Purchase orders
        +-- Audit ledger
        +-- Provider abstraction
                |
                +-- MOCK (enabled)
                +-- Stripe Issuing (disabled adapter)
                +-- Marqeta (disabled adapter)
```

## Core invariant

A SpendCore `Budget` represents **purchase authority granted by a business**, not a customer bank account, wallet, deposit balance, or transferable stored value.

Every card intent must be tied to a concrete `PurchaseRequest`. The MVP does not support ATM, cash-out, P2P transfers, transfers between customers, or external deposits.

## Money model

All amounts are stored as integer cents.

`remaining = limit - used - reserved`

- `limit`: maximum business-authorized purchase amount.
- `reserved`: approved purchase requests awaiting capture.
- `used`: captured purchases net of refunds.

This avoids double-spending the same authorization while a card transaction is pending.

## Provider safety

SpendCore should never persist raw PAN, CVV, magnetic-stripe data, or card PIN. Real card display/entry must use the selected provider's PCI-compliant components.

## Production hardening backlog

- Authentication and per-business API keys/OAuth
- Row-level tenant isolation
- Idempotency keys for all write endpoints
- Webhook signature validation
- PostgreSQL and database migrations
- Distributed locks/serializable budget reservations
- Provider-specific merchant/MCC controls
- JIT authorization webhook with strict response deadline
- Secrets manager
- Admin roles and approvals
- Metrics, alerting, immutable audit export
- Data-retention policy
- Jurisdiction/provider program configuration
