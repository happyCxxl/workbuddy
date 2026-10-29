"""工作区：会话归属目录（新表）+ 会话工作区外键。"""

import sqlalchemy as sa
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """建 workspace 表并给 ai_session 加 workspace_id。"""
    op.create_table(
        "workspace",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("path", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.UniqueConstraint("path", name="uq_workspace_path"),
    )
    op.add_column("ai_session", sa.Column("workspace_id", sa.BigInteger(), nullable=True))
    op.create_foreign_key("fk_ai_session_workspace", "ai_session", "workspace", ["workspace_id"], ["id"])


def downgrade() -> None:
    """回滚：先删外键与列，再删表。"""
    op.drop_constraint("fk_ai_session_workspace", "ai_session", type_="foreignkey")
    op.drop_column("ai_session", "workspace_id")
    op.drop_table("workspace")
