from __future__ import annotations

from abc import ABC, abstractmethod
from datetime import datetime, timezone
import os
import secrets
import uuid

import httpx

from .models import CardIntent


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class CardProvider(ABC):
    """Provider boundary. PAN/CVV must never be persisted by SpendCore."""

    name = "UNKNOWN"

    @abstractmethod
    def create_purchase_card(
        self, *, purchase_id: str, merchant: str, amount_cents: int, customer_ref: str | None = None
    ) -> CardIntent:
        raise NotImplementedError

    @abstractmethod
    def close_card(self, provider_ref: str) -> None:
        raise NotImplementedError

    @abstractmethod
    def get_card(self, provider_ref: str) -> dict:
        raise NotImplementedError

    @abstractmethod
    def status(self) -> dict:
        raise NotImplementedError


class MockCardProvider(CardProvider):
    """Safe development provider. Creates intents only; no payment card is issued."""

    name = "MOCK"

    def create_purchase_card(
        self, *, purchase_id: str, merchant: str, amount_cents: int, customer_ref: str | None = None
    ) -> CardIntent:
        ref = f"mock_{purchase_id}_{secrets.token_hex(4)}"
        return CardIntent(
            provider="MOCK",
            provider_ref=ref,
            last4=str(secrets.randbelow(9000) + 1000),
            max_amount_cents=amount_cents,
            merchant_lock=merchant,
            single_use=True,
            status="READY",
            user_ref=customer_ref,
            last_synced_at=now_iso(),
        )

    def close_card(self, provider_ref: str) -> None:
        return None

    def get_card(self, provider_ref: str) -> dict:
        return {
            "token": provider_ref,
            "last_four": provider_ref[-4:] if len(provider_ref) >= 4 else "0000",
            "state": "ACTIVE",
            "expiration": None,
        }

    def status(self) -> dict:
        return {
            "provider": self.name,
            "configured": True,
            "environment": "local",
            "real_money_enabled": False,
            "message": "Mock provider active; no real or sandbox card is issued.",
        }


class MarqetaProvider(CardProvider):
    """Marqeta Core API adapter for developer/private sandbox use.

    Required environment variables:
      MARQETA_APPLICATION_TOKEN
      MARQETA_ADMIN_ACCESS_TOKEN
      MARQETA_CARD_PRODUCT_TOKEN
      MARQETA_USER_TOKEN

    SPENDCORE_PROVIDER must be MARQETA to activate this adapter.
    """

    name = "MARQETA"

    def __init__(self) -> None:
        self.base_url = os.environ.get("MARQETA_BASE_URL", "https://sandbox-api.marqeta.com/v3").rstrip("/")
        self.application_token = os.environ.get("MARQETA_APPLICATION_TOKEN", "")
        self.admin_access_token = os.environ.get("MARQETA_ADMIN_ACCESS_TOKEN", "")
        self.card_product_token = os.environ.get("MARQETA_CARD_PRODUCT_TOKEN", "")
        self.user_token = os.environ.get("MARQETA_USER_TOKEN", "")
        self.timeout = float(os.environ.get("MARQETA_TIMEOUT_SECONDS", "15"))

    @property
    def configured(self) -> bool:
        return all(
            [
                self.application_token,
                self.admin_access_token,
                self.card_product_token,
                self.user_token,
            ]
        )

    def _request(self, method: str, path: str, *, json: dict | None = None) -> dict:
        if not self.configured:
            raise RuntimeError(
                "Marqeta is selected but sandbox credentials are incomplete. Configure application token, admin access token, card product token and user token."
            )
        url = f"{self.base_url}/{path.lstrip('/')}"
        with httpx.Client(
            auth=(self.application_token, self.admin_access_token),
            timeout=self.timeout,
            headers={"Accept": "application/json", "Content-Type": "application/json"},
        ) as client:
            response = client.request(method, url, json=json)
        if response.is_error:
            detail = response.text[:600]
            raise RuntimeError(f"Marqeta {response.status_code}: {detail}")
        return response.json() if response.content else {}

    def create_purchase_card(
        self, *, purchase_id: str, merchant: str, amount_cents: int, customer_ref: str | None = None
    ) -> CardIntent:
        # Marqeta allows a client-supplied token up to the platform token limits. UUID keeps it unique.
        card_token = f"sc-{uuid.uuid4()}"[:36]
        data = self._request(
            "POST",
            "/cards",
            json={
                "token": card_token,
                "user_token": self.user_token,
                "card_product_token": self.card_product_token,
            },
        )
        return CardIntent(
            provider="MARQETA",
            provider_ref=data["token"],
            last4=data.get("last_four", "????"),
            max_amount_cents=amount_cents,
            merchant_lock=merchant,
            single_use=True,
            status=data.get("state", "ACTIVE"),
            expiration=data.get("expiration"),
            user_ref=data.get("user_token", customer_ref),
            last_synced_at=now_iso(),
        )

    def close_card(self, provider_ref: str) -> None:
        # Card state changes in Marqeta are performed through card transitions.
        self._request(
            "POST",
            "/cardtransitions",
            json={
                "card_token": provider_ref,
                "state": "TERMINATED",
                "channel": "API",
                "reason_code": "00",
            },
        )

    def get_card(self, provider_ref: str) -> dict:
        return self._request("GET", f"/cards/{provider_ref}")

    def status(self) -> dict:
        return {
            "provider": self.name,
            "configured": self.configured,
            "environment": "sandbox" if "sandbox" in self.base_url.lower() else "custom",
            "base_url": self.base_url,
            "real_money_enabled": False,
            "message": (
                "Marqeta sandbox configured. Cards are test cards only."
                if self.configured
                else "Marqeta selected but credentials are incomplete."
            ),
        }


class StripeIssuingProvider(CardProvider):
    name = "STRIPE"

    def create_purchase_card(
        self, *, purchase_id: str, merchant: str, amount_cents: int, customer_ref: str | None = None
    ) -> CardIntent:
        raise RuntimeError("Stripe provider is disabled until an approved issuing program is configured.")

    def close_card(self, provider_ref: str) -> None:
        raise RuntimeError("Stripe provider is not configured.")

    def get_card(self, provider_ref: str) -> dict:
        raise RuntimeError("Stripe provider is not configured.")

    def status(self) -> dict:
        return {
            "provider": self.name,
            "configured": False,
            "environment": "disabled",
            "real_money_enabled": False,
            "message": "Stripe Issuing adapter is not enabled.",
        }


def build_provider() -> CardProvider:
    mode = os.environ.get("SPENDCORE_PROVIDER", "MOCK").strip().upper()
    if mode == "MARQETA":
        return MarqetaProvider()
    if mode == "STRIPE":
        return StripeIssuingProvider()
    return MockCardProvider()
