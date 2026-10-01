# SpendCore backend

## Run locally

```bash
cd backend
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload
```

OpenAPI docs are available at `/docs` while the API is running.

The default provider is `MOCK`. It never creates a real payment card and never moves money.

### Example flow

1. `POST /v1/businesses`
2. `POST /v1/budgets`
3. `POST /v1/purchase-requests`
4. Provider creates a single-purpose card intent.
5. `POST /v1/purchase-requests/{id}/capture`
6. Optional `POST /v1/purchase-requests/{id}/refund`

Amounts are integer cents to avoid floating-point money errors.
