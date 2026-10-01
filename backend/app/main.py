from __future__ import annotations

import hashlib
import json
import os
import secrets
import uuid
from datetime import datetime, timezone
from typing import Any

from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import HTTPBasic, HTTPBasicCredentials

from .engine import authorize
from .models import (
    AuditEvent,
    Budget,
    BudgetCreate,
    Business,
    BusinessCreate,
    CaptureRequest,
    PurchaseRequest,
    PurchaseRequestCreate,
    PurchaseStatus,
    RefundRequest,
)
from .provider import build_provider
from .store import Store

app = FastAPI(title="SpendCore API", version="0.4.0")
store = Store(os.environ.get("SPENDCORE_DB", "spendcore.db"))
provider = build_provider()
webhook_security = HTTPBasic(auto_error=False)


def ident(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def audit(business_id: str, event_type: str, entity_id: str, detail: dict) -> None:
    store.add_audit(AuditEvent(
        id=ident("evt"), business_id=business_id, event_type=event_type, entity_id=entity_id, detail=detail
    ))


def card_summary(purchase: PurchaseRequest) -> dict:
    card = purchase.card_intent
    budget = store.get_budget(purchase.budget_id)
    return {
        "purchase_id": purchase.id,
        "business_id": purchase.business_id,
        "budget_id": purchase.budget_id,
        "customer_ref": budget.customer_ref if budget else None,
        "order_ref": budget.order_ref if budget else purchase.external_order_ref,
        "merchant": purchase.merchant,
        "amount_cents": purchase.amount_cents,
        "purchase_status": purchase.status.value,
        "provider": card.provider if card else None,
        "provider_ref": card.provider_ref if card else None,
        "last4": card.last4 if card else None,
        "card_status": card.status if card else None,
        "expiration": card.expiration if card else None,
        "single_use": card.single_use if card else None,
        "max_amount_cents": card.max_amount_cents if card else None,
        "merchant_lock": card.merchant_lock if card else None,
        "last_synced_at": card.last_synced_at if card else None,
    }


def sync_card(purchase: PurchaseRequest) -> PurchaseRequest:
    card = purchase.card_intent
    if not card or card.provider != provider.name:
        return purchase
    data = provider.get_card(card.provider_ref)
    card.last4 = data.get("last_four", card.last4)
    card.status = data.get("state", card.status)
    card.expiration = data.get("expiration", card.expiration)
    card.user_ref = data.get("user_token", card.user_ref)
    card.last_synced_at = now_iso()
    store.put_purchase(purchase)
    return purchase


def cents_from_event(event: dict, fallback: int = 0) -> int:
    value = event.get("amount", fallback / 100.0 if fallback else 0)
    if isinstance(value, dict):
        value = value.get("amount", 0)
    try:
        return max(0, int(round(float(value) * 100)))
    except (TypeError, ValueError):
        return fallback


def webhook_events(payload: Any) -> list[dict]:
    if isinstance(payload, list):
        return [item for item in payload if isinstance(item, dict)]
    if not isinstance(payload, dict):
        return []
    nested: list[dict] = []
    for value in payload.values():
        if isinstance(value, list) and all(isinstance(item, dict) for item in value):
            nested.extend(value)
    return nested or [payload]


@app.get("/health")
def health():
    info = provider.status()
    return {"status": "ok", **info}


@app.get("/v1/provider")
def provider_status():
    return provider.status()


@app.post("/v1/businesses", response_model=Business)
def create_business(body: BusinessCreate):
    business = Business(id=ident("biz"), name=body.name, currency=body.currency.upper())
    store.put_business(business)
    audit(business.id, "BUSINESS_CREATED", business.id, {"name": business.name})
    return business


@app.get("/v1/businesses", response_model=list[Business])
def list_businesses():
    return store.list_businesses()


@app.post("/v1/budgets", response_model=Budget)
def create_budget(body: BudgetCreate):
    if not store.get_business(body.business_id):
        raise HTTPException(404, "Business not found")
    budget = Budget(
        id=ident("bg"), business_id=body.business_id, customer_ref=body.customer_ref,
        order_ref=body.order_ref, limit_cents=body.limit_cents, rules=body.rules
    )
    store.put_budget(budget)
    audit(body.business_id, "BUDGET_CREATED", budget.id, {"limit_cents": budget.limit_cents, "order_ref": budget.order_ref})
    return budget


@app.get("/v1/budgets", response_model=list[Budget])
def list_budgets():
    return store.list_budgets()


@app.get("/v1/budgets/{budget_id}")
def get_budget(budget_id: str):
    budget = store.get_budget(budget_id)
    if not budget:
        raise HTTPException(404, "Budget not found")
    return {**budget.model_dump(), "remaining_cents": budget.remaining_cents}


@app.post("/v1/purchase-requests", response_model=PurchaseRequest)
def create_purchase_request(body: PurchaseRequestCreate):
    if body.external_order_ref:
        existing = store.find_purchase_by_external_order_ref(body.business_id, body.external_order_ref)
        if existing:
            return existing

    budget = store.get_budget(body.budget_id)
    if not budget:
        raise HTTPException(404, "Budget not found")
    purchase_id = ident("pur")
    decision = authorize(budget, body)
    if not decision.approved:
        purchase = PurchaseRequest(
            id=purchase_id, business_id=body.business_id, budget_id=body.budget_id,
            merchant=body.merchant, amount_cents=body.amount_cents, currency=body.currency.upper(),
            category=body.category, external_order_ref=body.external_order_ref,
            status=PurchaseStatus.DECLINED, decline_reason=decision.reason,
        )
        store.put_purchase(purchase)
        audit(body.business_id, "PURCHASE_DECLINED", purchase.id, {"reason": decision.reason, "amount_cents": body.amount_cents})
        return purchase

    try:
        card = provider.create_purchase_card(
            purchase_id=purchase_id,
            merchant=body.merchant,
            amount_cents=body.amount_cents,
            customer_ref=budget.customer_ref,
        )
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc

    budget.reserved_cents += body.amount_cents
    purchase = PurchaseRequest(
        id=purchase_id, business_id=body.business_id, budget_id=body.budget_id,
        merchant=body.merchant, amount_cents=body.amount_cents, currency=body.currency.upper(),
        category=body.category, external_order_ref=body.external_order_ref,
        status=PurchaseStatus.APPROVED, card_intent=card,
    )
    store.put_budget(budget)
    store.put_purchase(purchase)
    audit(body.business_id, "PURCHASE_APPROVED", purchase.id, {
        "amount_cents": body.amount_cents, "provider": card.provider, "card_last4": card.last4
    })
    return purchase


@app.get("/v1/purchase-requests", response_model=list[PurchaseRequest])
def list_purchase_requests():
    return store.list_purchases()


@app.get("/v1/cards")
def list_cards():
    return [card_summary(p) for p in store.list_purchases() if p.card_intent]


@app.post("/v1/cards/sync")
def sync_all_cards():
    synced = 0
    errors: list[dict] = []
    for purchase in store.list_purchases():
        if not purchase.card_intent or purchase.card_intent.provider != provider.name:
            continue
        try:
            sync_card(purchase)
            synced += 1
        except RuntimeError as exc:
            errors.append({"purchase_id": purchase.id, "error": str(exc)})
    return {"synced": synced, "errors": errors, "cards": list_cards()}


@app.post("/v1/cards/{provider_ref}/sync")
def sync_one_card(provider_ref: str):
    purchase = store.find_purchase_by_provider_ref(provider_ref)
    if not purchase:
        raise HTTPException(404, "Card not found")
    try:
        sync_card(purchase)
    except RuntimeError as exc:
        raise HTTPException(503, str(exc)) from exc
    return card_summary(purchase)


@app.post("/v1/purchase-requests/{purchase_id}/capture", response_model=PurchaseRequest)
def capture_purchase(purchase_id: str, body: CaptureRequest):
    purchase = store.get_purchase(purchase_id)
    if not purchase:
        raise HTTPException(404, "Purchase not found")
    if purchase.status != PurchaseStatus.APPROVED:
        raise HTTPException(409, "Purchase is not awaiting capture")
    if body.amount_cents > purchase.amount_cents:
        raise HTTPException(409, "Capture exceeds authorized amount")
    budget = store.get_budget(purchase.budget_id)
    if not budget:
        raise HTTPException(500, "Budget missing")
    budget.reserved_cents = max(0, budget.reserved_cents - purchase.amount_cents)
    budget.used_cents += body.amount_cents
    purchase.captured_cents = body.amount_cents
    purchase.status = PurchaseStatus.CAPTURED
    if purchase.card_intent:
        try:
            provider.close_card(purchase.card_intent.provider_ref)
            purchase.card_intent.status = "TERMINATED" if provider.name == "MARQETA" else "CLOSED"
            purchase.card_intent.last_synced_at = now_iso()
        except RuntimeError as exc:
            audit(purchase.business_id, "CARD_CLOSE_FAILED", purchase.id, {"error": str(exc)})
    store.put_budget(budget)
    store.put_purchase(purchase)
    audit(purchase.business_id, "PURCHASE_CAPTURED", purchase.id, {"amount_cents": body.amount_cents})
    return purchase


@app.post("/v1/purchase-requests/{purchase_id}/refund", response_model=PurchaseRequest)
def refund_purchase(purchase_id: str, body: RefundRequest):
    purchase = store.get_purchase(purchase_id)
    if not purchase:
        raise HTTPException(404, "Purchase not found")
    refundable = purchase.captured_cents - purchase.refunded_cents
    if body.amount_cents > refundable:
        raise HTTPException(409, "Refund exceeds captured amount")
    budget = store.get_budget(purchase.budget_id)
    if not budget:
        raise HTTPException(500, "Budget missing")
    budget.used_cents = max(0, budget.used_cents - body.amount_cents)
    purchase.refunded_cents += body.amount_cents
    if purchase.refunded_cents == purchase.captured_cents:
        purchase.status = PurchaseStatus.REFUNDED
    store.put_budget(budget)
    store.put_purchase(purchase)
    audit(purchase.business_id, "PURCHASE_REFUNDED", purchase.id, {"amount_cents": body.amount_cents})
    return purchase


@app.post("/v1/webhooks/marqeta")
def marqeta_webhook(
    payload: Any,
    credentials: HTTPBasicCredentials | None = Depends(webhook_security),
):
    expected_user = os.environ.get("MARQETA_WEBHOOK_USERNAME", "")
    expected_password = os.environ.get("MARQETA_WEBHOOK_PASSWORD", "")
    if expected_user or expected_password:
        valid = credentials is not None
        if valid:
            valid = secrets.compare_digest(credentials.username, expected_user) and secrets.compare_digest(
                credentials.password, expected_password
            )
        if not valid:
            raise HTTPException(401, "Invalid webhook credentials")

    processed = 0
    skipped = 0
    for event in webhook_events(payload):
        raw = json.dumps(event, sort_keys=True, default=str)
        event_key = str(event.get("token") or hashlib.sha256(raw.encode()).hexdigest())
        event_type = str(event.get("type") or event.get("event_type") or "unknown")
        card_obj = event.get("card") if isinstance(event.get("card"), dict) else {}
        card_token = event.get("card_token") or card_obj.get("token")
        if not card_token:
            skipped += 1
            continue
        unique_key = f"{event_type}:{event_key}:{card_token}"
        if store.webhook_seen(unique_key):
            skipped += 1
            continue

        purchase = store.find_purchase_by_provider_ref(str(card_token))
        if not purchase:
            store.mark_webhook_seen(unique_key)
            skipped += 1
            continue

        card = purchase.card_intent
        if card:
            state = event.get("state") or card_obj.get("state")
            if state:
                card.status = str(state)
            last4 = card_obj.get("last_four") or event.get("last_four")
            if last4:
                card.last4 = str(last4)
            card.last_synced_at = now_iso()

        budget = store.get_budget(purchase.budget_id)
        lower_type = event_type.lower()
        amount_cents = cents_from_event(event, purchase.amount_cents)

        if budget and "authorization.clearing" in lower_type and purchase.status == PurchaseStatus.APPROVED:
            capture = min(amount_cents or purchase.amount_cents, purchase.amount_cents)
            budget.reserved_cents = max(0, budget.reserved_cents - purchase.amount_cents)
            budget.used_cents += capture
            purchase.captured_cents = capture
            purchase.status = PurchaseStatus.CAPTURED
            if card:
                card.status = "USED"
            store.put_budget(budget)
        elif budget and "authorization.reversal" in lower_type and purchase.status == PurchaseStatus.APPROVED:
            budget.reserved_cents = max(0, budget.reserved_cents - purchase.amount_cents)
            purchase.status = PurchaseStatus.CANCELLED
            if card:
                card.status = "REVERSED"
            store.put_budget(budget)
        elif budget and "refund" in lower_type and purchase.captured_cents > purchase.refunded_cents:
            refundable = purchase.captured_cents - purchase.refunded_cents
            refund = min(amount_cents or refundable, refundable)
            budget.used_cents = max(0, budget.used_cents - refund)
            purchase.refunded_cents += refund
            if purchase.refunded_cents >= purchase.captured_cents:
                purchase.status = PurchaseStatus.REFUNDED
            store.put_budget(budget)

        store.put_purchase(purchase)
        store.mark_webhook_seen(unique_key)
        audit(purchase.business_id, "MARQETA_WEBHOOK", purchase.id, {
            "type": event_type, "card_token": str(card_token), "amount_cents": amount_cents
        })
        processed += 1

    return {"ok": True, "processed": processed, "skipped": skipped}


@app.get("/v1/audit/{business_id}")
def get_audit(business_id: str):
    return store.list_audit(business_id)
