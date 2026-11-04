"""Shared HTTP response schemas."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class ErrorEnvelope(BaseModel):
    """Stable public error response without internal exception details."""

    model_config = ConfigDict(populate_by_name=True)

    code: str
    message: str
    data: Any | None = None
    request_id: str = Field(alias="requestId")


class ModelProvenance(BaseModel):
    """Safe model-call provenance shared by typed semantic responses."""

    model_config = ConfigDict(populate_by_name=True)

    operation: str
    model: str
    duration_ms: int = Field(alias="durationMs")
