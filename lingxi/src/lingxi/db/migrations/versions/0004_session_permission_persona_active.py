"""会话权限档与人格启用标记。

Revision ID: 0004
Revises: 0002
Create Date: 2026-10-28
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "ai_session", sa.Column("permission_preset", sa.String(length=16), server_default="standard", nullable=False)
    )
    op.add_column("persona_version", sa.Column("active", sa.Boolean(), server_default=sa.false(), nullable=False))
    op.execute("UPDATE persona_version SET active = true WHERE version = (SELECT max(version) FROM persona_version)")


def downgrade() -> None:
    op.drop_column("persona_version", "active")
    op.drop_column("ai_session", "permission_preset")
