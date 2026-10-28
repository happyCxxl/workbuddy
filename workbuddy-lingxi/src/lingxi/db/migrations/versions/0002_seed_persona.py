"""人格首版种子：任何环境起来即有默认人格（可乐）。

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-28
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# 迁移须自包含：默认字段在此冻结，与应用代码的默认值无引用关系
_DEFAULT_FIELDS = {
    "name": "可乐",
    "opening": "你好，我是可乐，你的 AI 助理。有活尽管安排。",
    "catchphrases": ["明白", "收到", "好嘞"],
    "tone": "轻松直接",
    "style": "先给结论，再补细节；不说废话",
    "boundaries": "涉及删除、覆盖、工作目录之外的操作先问再动",
    "user_name": "老板",
    "user_profile": "",
}


def upgrade() -> None:
    table = sa.table("persona_version", sa.column("version", sa.Integer), sa.column("fields", postgresql.JSONB))
    op.bulk_insert(table, [{"version": 1, "fields": _DEFAULT_FIELDS}])


def downgrade() -> None:
    op.execute("DELETE FROM persona_version WHERE version = 1")
