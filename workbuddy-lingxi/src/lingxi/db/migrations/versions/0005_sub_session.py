"""后台任务表与会话 kind 列：S17 子会话（干净上下文的任务执行器）。"""

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """加会话 kind 列与 sub_session 表。"""
    op.add_column("ai_session", sa.Column("kind", sa.String(length=8), nullable=False, server_default="chat"))
    op.create_table(
        "sub_session",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("parent_session_id", sa.BigInteger(), nullable=False),
        sa.Column("child_session_id", sa.BigInteger(), nullable=False),
        sa.Column("title", sa.String(length=255), nullable=False, server_default=""),
        sa.Column("task", sa.Text(), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False, server_default="running"),
        sa.Column("result", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["parent_session_id"], ["ai_session.id"]),
        sa.ForeignKeyConstraint(["child_session_id"], ["ai_session.id"]),
        sa.UniqueConstraint("child_session_id", name="uq_sub_session_child"),
    )
    op.create_index("ix_sub_session_parent", "sub_session", ["parent_session_id"])


def downgrade() -> None:
    """回滚：删表与列。"""
    op.drop_index("ix_sub_session_parent", table_name="sub_session")
    op.drop_table("sub_session")
    op.drop_column("ai_session", "kind")
