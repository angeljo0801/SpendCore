import os
from pathlib import Path

os.environ["SPENDCORE_DB"] = "/tmp/spendcore-test.db"
os.environ["SPENDCORE_PROVIDER"] = "MOCK"
Path(os.environ["SPENDCORE_DB"]).unlink(missing_ok=True)

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def create_flow():
    biz = client.post("/v1/businesses", json={"name": "Alas Cargo", "currency": "USD"}).json()
    budget = client.post("/v1/budgets", json={
        "business_id": biz["id"],
        "customer_ref": "Juan",
        "order_ref": "AC-1048",
        "limit_cents": 60000,
        "rules": {"allowed_merchants": ["Amazon"], "blocked_categories": ["ATM", "MONEY_TRANSFER"], "single_purpose_only": True}
    }).json()
    purchase = client.post("/v1/purchase-requests", json={
        "business_id": biz["id"], "budget_id": budget["id"], "merchant": "Amazon", "amount_cents": 18532, "currency": "USD"
    }).json()
    return biz, budget, purchase


def test_provider_status_and_card_sync():
    status = client.get("/v1/provider").json()
    assert status["provider"] == "MOCK"
    assert status["configured"] is True

    _, _, purchase = create_flow()
    cards = client.get("/v1/cards").json()
    card = next(c for c in cards if c["purchase_id"] == purchase["id"])
    assert card["provider"] == "MOCK"
    assert len(card["last4"]) == 4
    assert card["customer_ref"] == "Juan"

    synced = client.post("/v1/cards/sync").json()
    assert synced["synced"] >= 1


def test_end_to_end_purchase_capture_refund():
    _, budget, purchase = create_flow()
    assert purchase["status"] == "APPROVED"
    assert purchase["card_intent"]["provider"] == "MOCK"

    captured = client.post(f"/v1/purchase-requests/{purchase['id']}/capture", json={"amount_cents": 18532}).json()
    assert captured["status"] == "CAPTURED"

    refunded = client.post(f"/v1/purchase-requests/{purchase['id']}/refund", json={"amount_cents": 5000}).json()
    assert refunded["refunded_cents"] == 5000

    updated_budget = client.get(f"/v1/budgets/{budget['id']}").json()
    assert updated_budget["remaining_cents"] == 46468
