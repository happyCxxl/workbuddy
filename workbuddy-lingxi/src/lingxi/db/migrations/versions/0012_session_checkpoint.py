"""会话检查点：按回合保存文件快照，支持 /rewind 回退操作。"""

import sqlalchemy as sa
from alembic import op

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """新增会话检查点表：文件清单存 JSONB，快照文件放磁盘。"""
    op.create_table(
        "session_checkpoint",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("session_id", sa.BigInteger(), sa.ForeignKey("ai_session.id"), nullable=False),
        sa.Column("turn_seq", sa.Integer(), nullable=False),
        sa.Column("prompt", sa.Text(), nullable=False, server_default=""),
        sa.Column("files", sa.JSON(), nullable=False, server_default="[]"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_session_checkpoint_session_turn", "session_checkpoint", ["session_id", "turn_seq"])


def downgrade() -> None:
    """回退：删除检查点表。"""
    op.drop_index("ix_session_checkpoint_session_turn", table_name="session_checkpoint")
    op.drop_table("session_checkpoint")
