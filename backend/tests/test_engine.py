from app.engine import authorize
from app.models import Budget, PurchaseRequestCreate, RuleSet


def test_budget_limit_is_enforced():
    budget = Budget(
        id="bg_1", business_id="biz_1", customer_ref="juan", order_ref="AC-1",
        limit_cents=60000, used_cents=59000,
    )
    req = PurchaseRequestCreate(
        business_id="biz_1", budget_id="bg_1", merchant="Amazon", amount_cents=2000
    )
    decision = authorize(budget, req)
    assert not decision.approved
    assert decision.reason == "BUDGET_LIMIT_EXCEEDED"


def test_merchant_allowlist_is_enforced():
    budget = Budget(
        id="bg_1", business_id="biz_1", customer_ref="juan", order_ref="AC-1",
        limit_cents=60000, rules=RuleSet(allowed_merchants=["Amazon", "Walmart"])
    )
    req = PurchaseRequestCreate(
        business_id="biz_1", budget_id="bg_1", merchant="Cash App", amount_cents=1000
    )
    decision = authorize(budget, req)
    assert not decision.approved
    assert decision.reason == "MERCHANT_NOT_ALLOWED"


def test_valid_purchase_is_approved():
    budget = Budget(
        id="bg_1", business_id="biz_1", customer_ref="juan", order_ref="AC-1", limit_cents=60000,
        rules=RuleSet(allowed_merchants=["Amazon"], max_purchase_cents=30000)
    )
    req = PurchaseRequestCreate(
        business_id="biz_1", budget_id="bg_1", merchant="Amazon", amount_cents=18532
    )
    assert authorize(budget, req).approved
