from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class PurchaseStatus(str, Enum):
    APPROVED = "APPROVED"
    DECLINED = "DECLINED"
    CAPTURED = "CAPTURED"
    REFUNDED = "REFUNDED"
    CANCELLED = "CANCELLED"


class BusinessCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    currency: str = Field(default="USD", min_length=3, max_length=3)


class Business(BaseModel):
    id: str
    name: str
    currency: str
    created_at: str = Field(default_factory=now_iso)


class RuleSet(BaseModel):
    allowed_merchants: list[str] = []
    blocked_categories: list[str] = ["ATM", "MONEY_TRANSFER", "CASH_EQUIVALENT", "CRYPTO"]
    max_purchase_cents: Optional[int] = Field(default=None, ge=1)
    single_purpose_only: bool = True


class BudgetCreate(BaseModel):
    business_id: str
    customer_ref: str = Field(min_length=1, max_length=120)
    order_ref: str = Field(min_length=1, max_length=120)
    limit_cents: int = Field(gt=0)
    rules: RuleSet = Field(default_factory=RuleSet)


class Budget(BaseModel):
    id: str
    business_id: str
    customer_ref: str
    order_ref: str
    limit_cents: int
    used_cents: int = 0
    reserved_cents: int = 0
    rules: RuleSet = Field(default_factory=RuleSet)
    status: str = "ACTIVE"
    created_at: str = Field(default_factory=now_iso)

    @property
    def remaining_cents(self) -> int:
        return max(0, self.limit_cents - self.used_cents - self.reserved_cents)


class PurchaseRequestCreate(BaseModel):
    business_id: str
    budget_id: str
    merchant: str = Field(min_length=1, max_length=160)
    amount_cents: int = Field(gt=0)
    currency: str = Field(default="USD", min_length=3, max_length=3)
    category: Optional[str] = None
    external_order_ref: Optional[str] = None


class CardIntent(BaseModel):
    provider: str
    provider_ref: str
    last4: str
    max_amount_cents: int
    merchant_lock: str
    single_use: bool = True
    status: str = "READY"
    expiration: Optional[str] = None
    user_ref: Optional[str] = None
    last_synced_at: Optional[str] = None


class PurchaseRequest(BaseModel):
    id: str
    business_id: str
    budget_id: str
    merchant: str
    amount_cents: int
    currency: str
    category: Optional[str] = None
    external_order_ref: Optional[str] = None
    status: PurchaseStatus
    decline_reason: Optional[str] = None
    card_intent: Optional[CardIntent] = None
    captured_cents: int = 0
    refunded_cents: int = 0
    created_at: str = Field(default_factory=now_iso)


class CaptureRequest(BaseModel):
    amount_cents: int = Field(gt=0)


class RefundRequest(BaseModel):
    amount_cents: int = Field(gt=0)


class AuditEvent(BaseModel):
    id: str
    business_id: str
    event_type: str
    entity_id: str
    detail: dict
    created_at: str = Field(default_factory=now_iso)
