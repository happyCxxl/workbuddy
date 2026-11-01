"""模型能力层：厂商、模型、调用记录三张表，会话增加当前模型。"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """建模型能力层三张表，并给会话加 model_id。"""
    op.create_table(
        "model_vendor",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("code", sa.String(length=64), nullable=False),
        sa.Column("display_name", sa.String(length=128), nullable=False),
        sa.Column("base_url", sa.Text(), nullable=False),
        sa.Column("api_key_env", sa.String(length=128), nullable=False),
        sa.Column("timeout_ms", sa.Integer(), nullable=True),
        sa.Column("max_retries", sa.Integer(), nullable=True),
        sa.Column("enabled", sa.Boolean(), server_default="true", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.UniqueConstraint("code", name="uq_model_vendor_code"),
    )
    op.create_table(
        "model",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("vendor_id", sa.BigInteger(), nullable=False),
        sa.Column("model_key", sa.String(length=255), nullable=False),
        sa.Column("display_name", sa.String(length=255), nullable=False),
        sa.Column("capability", sa.String(length=16), nullable=False),
        sa.Column("dimension", sa.Integer(), nullable=True),
        sa.Column("context_window", sa.Integer(), nullable=True),
        sa.Column("batch_limit", sa.Integer(), nullable=True),
        sa.Column("rpm_limit", sa.Integer(), nullable=True),
        sa.Column("tpm_limit", sa.Integer(), nullable=True),
        sa.Column("price_in", sa.Numeric(12, 6), nullable=True),
        sa.Column("price_out", sa.Numeric(12, 6), nullable=True),
        sa.Column("currency", sa.String(length=8), server_default="CNY", nullable=False),
        sa.Column(
            "features",
            postgresql.JSONB(),
            server_default=sa.text("'{}'::jsonb"),
            nullable=False,
        ),
        sa.Column("enabled", sa.Boolean(), server_default="true", nullable=False),
        sa.Column("status", sa.String(length=16), server_default="UNKNOWN", nullable=False),
        sa.Column("last_checked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
        sa.ForeignKeyConstraint(["vendor_id"], ["model_vendor.id"], name="fk_model_vendor"),
        sa.UniqueConstraint("vendor_id", "model_key", "capability", name="uq_model_vendor_key_cap"),
    )
    op.create_index("ix_model_vendor_id", "model", ["vendor_id"])
    op.create_index("ix_model_capability_enabled", "model", ["capability", "enabled"])
    op.create_table(
        "model_gateway_call",
        sa.Column("id", sa.BigInteger(), sa.Identity(), primary_key=True),
        sa.Column("request_id", sa.String(length=64), nullable=False),
        sa.Column("source", sa.String(length=64), nullable=False),
        sa.Column("capability", sa.String(length=16), nullable=False),
        sa.Column("model_id", sa.BigInteger(), nullable=True),
        sa.Column("vendor_code", sa.String(length=64), server_default="", nullable=False),
        sa.Column("model_key", sa.String(length=255), server_default="", nullable=False),
        sa.Column("prompt_tokens", sa.Integer(), nullable=True),
        sa.Column("completion_tokens", sa.Integer(), nullable=True),
        sa.Column("total_tokens", sa.Integer(), nullable=True),
        sa.Column("latency_ms", sa.Integer(), nullable=True),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("error_code", sa.String(length=64), nullable=True),
        sa.Column("error_message", sa.Text(), nullable=True),
        sa.Column("attempts", sa.Integer(), server_default="1", nullable=False),
        sa.Column("fallback_used", sa.Boolean(), server_default="false", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.text("now()"), nullable=False),
    )
    op.create_index("ix_model_gateway_call_request_id", "model_gateway_call", ["request_id"])
    op.create_index("ix_model_gateway_call_source_time", "model_gateway_call", ["source", "created_at"])
    op.create_index("ix_model_gateway_call_model_time", "model_gateway_call", ["model_id", "created_at"])
    op.create_index("ix_model_gateway_call_capability_time", "model_gateway_call", ["capability", "created_at"])
    op.add_column("ai_session", sa.Column("model_id", sa.BigInteger(), nullable=True))
    op.create_foreign_key("fk_ai_session_model", "ai_session", "model", ["model_id"], ["id"])
    op.create_index("ix_ai_session_model_id", "ai_session", ["model_id"])


def downgrade() -> None:
    """回退：删会话模型关联与模型能力层三张表。"""
    op.drop_index("ix_ai_session_model_id", table_name="ai_session")
    op.drop_constraint("fk_ai_session_model", "ai_session", type_="foreignkey")
    op.drop_column("ai_session", "model_id")
    op.drop_index("ix_model_gateway_call_capability_time", table_name="model_gateway_call")
    op.drop_index("ix_model_gateway_call_model_time", table_name="model_gateway_call")
    op.drop_index("ix_model_gateway_call_source_time", table_name="model_gateway_call")
    op.drop_index("ix_model_gateway_call_request_id", table_name="model_gateway_call")
    op.drop_table("model_gateway_call")
    op.drop_index("ix_model_capability_enabled", table_name="model")
    op.drop_index("ix_model_vendor_id", table_name="model")
    op.drop_table("model")
    op.drop_table("model_vendor")
