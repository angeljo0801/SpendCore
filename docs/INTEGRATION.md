# Integrating another business app

A connected app should send SpendCore **purchase intent**, not customer money balances.

## 1. Create a business

```http
POST /v1/businesses
Content-Type: application/json

{"name":"Alas Cargo","currency":"USD"}
```

## 2. Create a service/order budget

```http
POST /v1/budgets
Content-Type: application/json

{
  "business_id":"biz_...",
  "customer_ref":"customer-123",
  "order_ref":"AC-1048",
  "limit_cents":60000,
  "rules":{
    "allowed_merchants":["Amazon","Walmart","Shein","Temu"],
    "blocked_categories":["ATM","MONEY_TRANSFER","CASH_EQUIVALENT","CRYPTO"],
    "max_purchase_cents":30000,
    "single_purpose_only":true
  }
}
```

## 3. Ask to execute a concrete purchase

```http
POST /v1/purchase-requests
Content-Type: application/json

{
  "business_id":"biz_...",
  "budget_id":"bg_...",
  "merchant":"Amazon",
  "amount_cents":18532,
  "currency":"USD",
  "external_order_ref":"amazon-cart-331"
}
```

If approved, SpendCore reserves the amount and returns a provider `card_intent`. In development this is a MOCK intent only.

## Integration rule

Do not expose `remaining_cents` to customers as a bank-account balance or promise it is transferable cash. In a business UI it should be labeled similar to **Remaining purchase authorization** or **Available purchasing limit**, consistent with the actual contract and program.
