"""删除权限规则表：审批只保留一次性授权，不再持久化「始终允许」。"""

import sqlalchemy as sa
from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """删除 permission_rule 表。"""
    op.drop_table("permission_rule")


def downgrade() -> None:
    """恢复 permission_rule 表（结构同 0001）。"""
    op.create_table(
        "permission_rule",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("tool", sa.String(length=64), nullable=False),
        sa.Column("scope", sa.Text(), nullable=True),
        sa.Column("action", sa.String(length=16), nullable=False),
        sa.Column("level", sa.String(length=16), nullable=False),
        sa.Column("work_dir", sa.Text(), nullable=True),
        sa.Column("source", sa.String(length=32), server_default="user", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
