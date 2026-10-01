from __future__ import annotations

from abc import ABC, abstractmethod
import secrets
from .models import CardIntent


class CardProvider(ABC):
    """Provider boundary. Real adapters must keep PAN/CVV out of SpendCore logs and databases."""

    @abstractmethod
    def create_purchase_card(self, *, purchase_id: str, merchant: str, amount_cents: int) -> CardIntent:
        raise NotImplementedError

    @abstractmethod
    def close_card(self, provider_ref: str) -> None:
        raise NotImplementedError


class MockCardProvider(CardProvider):
    """Safe development provider. Creates intents only; it does not issue a payment card."""

    def create_purchase_card(self, *, purchase_id: str, merchant: str, amount_cents: int) -> CardIntent:
        ref = f"mock_{purchase_id}_{secrets.token_hex(4)}"
        return CardIntent(
            provider="MOCK",
            provider_ref=ref,
            last4="0000",
            max_amount_cents=amount_cents,
            merchant_lock=merchant,
            single_use=True,
            status="READY",
        )

    def close_card(self, provider_ref: str) -> None:
        return None


class StripeIssuingProvider(CardProvider):
    def create_purchase_card(self, *, purchase_id: str, merchant: str, amount_cents: int) -> CardIntent:
        raise RuntimeError("Stripe provider is intentionally disabled until an approved issuing program and credentials are configured.")

    def close_card(self, provider_ref: str) -> None:
        raise RuntimeError("Stripe provider is not configured.")


class MarqetaProvider(CardProvider):
    def create_purchase_card(self, *, purchase_id: str, merchant: str, amount_cents: int) -> CardIntent:
        raise RuntimeError("Marqeta provider is intentionally disabled until an approved program and credentials are configured.")

    def close_card(self, provider_ref: str) -> None:
        raise RuntimeError("Marqeta provider is not configured.")
