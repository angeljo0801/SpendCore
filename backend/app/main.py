from __future__ import annotations

import os
import uuid
from fastapi import FastAPI, HTTPException

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
from .provider import MockCardProvider
from .store import Store

app = FastAPI(title="SpendCore API", version="0.1.0")
store = Store(os.environ.get("SPENDCORE_DB", "spendcore.db"))
provider = MockCardProvider()


def ident(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def audit(business_id: str, event_type: str, entity_id: str, detail: dict) -> None:
    store.add_audit(AuditEvent(
        id=ident("evt"), business_id=business_id, event_type=event_type, entity_id=entity_id, detail=detail
    ))


@app.get("/health")
def health():
    return {"status": "ok", "provider": "MOCK", "real_money_enabled": False}


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

    budget.reserved_cents += body.amount_cents
    card = provider.create_purchase_card(purchase_id=purchase_id, merchant=body.merchant, amount_cents=body.amount_cents)
    purchase = PurchaseRequest(
        id=purchase_id, business_id=body.business_id, budget_id=body.budget_id,
        merchant=body.merchant, amount_cents=body.amount_cents, currency=body.currency.upper(),
        category=body.category, external_order_ref=body.external_order_ref,
        status=PurchaseStatus.APPROVED, card_intent=card,
    )
    store.put_budget(budget)
    store.put_purchase(purchase)
    audit(body.business_id, "PURCHASE_APPROVED", purchase.id, {"amount_cents": body.amount_cents, "provider": card.provider})
    return purchase


@app.get("/v1/purchase-requests", response_model=list[PurchaseRequest])
def list_purchase_requests():
    return store.list_purchases()


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
        provider.close_card(purchase.card_intent.provider_ref)
        purchase.card_intent.status = "CLOSED"
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


@app.get("/v1/audit/{business_id}")
def get_audit(business_id: str):
    return store.list_audit(business_id)
