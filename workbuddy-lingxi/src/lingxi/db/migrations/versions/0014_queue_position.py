"""会话输入队列：新增排序位。"""

import sqlalchemy as sa
from alembic import op

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """新增队列排序位，已有数据按 id 保持原顺序。"""
    op.add_column("session_input_queue", sa.Column("position", sa.Integer(), nullable=False, server_default="0"))
    op.execute("UPDATE session_input_queue SET position = id")


def downgrade() -> None:
    """回退：删除排序位。"""
    op.drop_column("session_input_queue", "position")
