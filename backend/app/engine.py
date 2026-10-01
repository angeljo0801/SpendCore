from __future__ import annotations

from .models import Budget, PurchaseRequestCreate


class AuthorizationDecision:
    def __init__(self, approved: bool, reason: str | None = None):
        self.approved = approved
        self.reason = reason


def authorize(budget: Budget, request: PurchaseRequestCreate) -> AuthorizationDecision:
    if budget.status != "ACTIVE":
        return AuthorizationDecision(False, "BUDGET_NOT_ACTIVE")
    if request.business_id != budget.business_id:
        return AuthorizationDecision(False, "BUSINESS_MISMATCH")
    if request.amount_cents > budget.remaining_cents:
        return AuthorizationDecision(False, "BUDGET_LIMIT_EXCEEDED")
    if budget.rules.max_purchase_cents and request.amount_cents > budget.rules.max_purchase_cents:
        return AuthorizationDecision(False, "PER_PURCHASE_LIMIT_EXCEEDED")
    if request.category and request.category.upper() in {x.upper() for x in budget.rules.blocked_categories}:
        return AuthorizationDecision(False, "CATEGORY_BLOCKED")
    allowed = [x.strip().lower() for x in budget.rules.allowed_merchants if x.strip()]
    if allowed and request.merchant.strip().lower() not in allowed:
        return AuthorizationDecision(False, "MERCHANT_NOT_ALLOWED")
    return AuthorizationDecision(True)
