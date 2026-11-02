"""会话检查点：按回合保存文件快照，支持 /rewind 回退操作。"""

import sqlalchemy as sa
from alembic import op

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """新增会话输入队列表。"""
    op.create_table(
        "session_input_queue",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("session_id", sa.BigInteger(), sa.ForeignKey("ai_session.id"), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False, server_default="pending"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index("ix_session_input_queue_session", "session_input_queue", ["session_id", "status"])


def downgrade() -> None:
    """回退：删除会话输入队列表。"""
    op.drop_index("ix_session_input_queue_session", table_name="session_input_queue")
    op.drop_table("session_input_queue")
