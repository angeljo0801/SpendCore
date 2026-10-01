from __future__ import annotations

import sqlite3
from threading import RLock
from .models import AuditEvent, Budget, Business, PurchaseRequest


class Store:
    def __init__(self, path: str = "spendcore.db") -> None:
        self.path = path
        self.lock = RLock()
        self._init_db()

    def _connect(self):
        conn = sqlite3.connect(self.path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS businesses (id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS budgets (id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS purchases (id TEXT PRIMARY KEY, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS audit (id TEXT PRIMARY KEY, business_id TEXT NOT NULL, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS webhook_events (event_key TEXT PRIMARY KEY, created_at DATETIME DEFAULT CURRENT_TIMESTAMP);
                """
            )

    def _put(self, table: str, obj) -> None:
        with self.lock, self._connect() as conn:
            conn.execute(
                f"INSERT OR REPLACE INTO {table} (id, payload) VALUES (?, ?)",
                (obj.id, obj.model_dump_json()),
            )

    def _get(self, table: str, obj_id: str, model):
        with self._connect() as conn:
            row = conn.execute(f"SELECT payload FROM {table} WHERE id = ?", (obj_id,)).fetchone()
        return model.model_validate_json(row["payload"]) if row else None

    def _list(self, table: str, model):
        with self._connect() as conn:
            rows = conn.execute(f"SELECT payload FROM {table} ORDER BY rowid DESC").fetchall()
        return [model.model_validate_json(r["payload"]) for r in rows]

    def put_business(self, obj: Business): self._put("businesses", obj)
    def get_business(self, obj_id: str): return self._get("businesses", obj_id, Business)
    def list_businesses(self): return self._list("businesses", Business)

    def put_budget(self, obj: Budget): self._put("budgets", obj)
    def get_budget(self, obj_id: str): return self._get("budgets", obj_id, Budget)
    def list_budgets(self): return self._list("budgets", Budget)

    def put_purchase(self, obj: PurchaseRequest): self._put("purchases", obj)
    def get_purchase(self, obj_id: str): return self._get("purchases", obj_id, PurchaseRequest)
    def list_purchases(self): return self._list("purchases", PurchaseRequest)

    def find_purchase_by_provider_ref(self, provider_ref: str):
        for purchase in self.list_purchases():
            if purchase.card_intent and purchase.card_intent.provider_ref == provider_ref:
                return purchase
        return None

    def add_audit(self, event: AuditEvent):
        with self.lock, self._connect() as conn:
            conn.execute(
                "INSERT INTO audit (id, business_id, payload) VALUES (?, ?, ?)",
                (event.id, event.business_id, event.model_dump_json()),
            )

    def list_audit(self, business_id: str):
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT payload FROM audit WHERE business_id = ? ORDER BY rowid DESC", (business_id,)
            ).fetchall()
        return [AuditEvent.model_validate_json(r["payload"]) for r in rows]

    def webhook_seen(self, event_key: str) -> bool:
        with self._connect() as conn:
            row = conn.execute("SELECT 1 FROM webhook_events WHERE event_key = ?", (event_key,)).fetchone()
        return row is not None

    def mark_webhook_seen(self, event_key: str) -> None:
        with self.lock, self._connect() as conn:
            conn.execute("INSERT OR IGNORE INTO webhook_events (event_key) VALUES (?)", (event_key,))
