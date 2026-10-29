"""模型调用明细增加缓存命中字段：观测 DeepSeek 前缀缓存效果。"""

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """加缓存命中/未命中 token 两列。"""
    op.add_column("model_call", sa.Column("prompt_cache_hit_tokens", sa.Integer(), nullable=True))
    op.add_column("model_call", sa.Column("prompt_cache_miss_tokens", sa.Integer(), nullable=True))


def downgrade() -> None:
    """回滚：删两列。"""
    op.drop_column("model_call", "prompt_cache_miss_tokens")
    op.drop_column("model_call", "prompt_cache_hit_tokens")
