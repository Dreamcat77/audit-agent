from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import (
    BudgetSemanticOperation,
    ContractTextOperation,
    ModelCompletion,
    ModelGatewayPort,
    SpreadsheetHeaderMappingOperation,
)


class HeaderMappingGateway(ModelGatewayPort):
    def __init__(self) -> None:
        self.operations: list[SpreadsheetHeaderMappingOperation] = []

    def analyze_contract(self, operation: ContractTextOperation) -> ModelCompletion:
        raise AssertionError("contract operation was not expected")

    def analyze_budget(self, operation: BudgetSemanticOperation) -> ModelCompletion:
        raise AssertionError("budget operation was not expected")

    def analyze_header_mapping(
        self, operation: SpreadsheetHeaderMappingOperation
    ) -> ModelCompletion:
        self.operations.append(operation)
        java_preview = operation.source_id == "quote-2"
        supplier_field = (
            "supplierName" if "supplierName" in operation.allowed_fields else "SUPPLIER_NAME"
        )
        quote_field = (
            "quoteAmount" if "quoteAmount" in operation.allowed_fields else "QUOTE_AMOUNT"
        )
        return ModelCompletion(
            payload={
                "headerRowNumber": 4 if java_preview else 2,
                "mappings": [
                    {
                        "sourceHeader": "公司名称" if java_preview else "公司名",
                        "sourceColumnIndex": 0,
                        "standardField": supplier_field,
                        "confidence": 0.95,
                    },
                    {
                        "sourceHeader": "金额" if java_preview else "价格",
                        "sourceColumnIndex": 1,
                        "standardField": quote_field,
                        "confidence": 0.92,
                    },
                ] + ([] if java_preview else [{
                        "sourceHeader": "备注",
                        "sourceColumnIndex": 2,
                        "standardField": None,
                        "confidence": 0.2,
                    }]),
            },
            model="qwen-test",
            duration_ms=8,
        )


def test_procurement_header_mapping_accepts_array_rows_and_returns_envelope() -> None:
    gateway = HeaderMappingGateway()
    client = TestClient(create_app(settings=Settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/procurement-header-mapping",
        json={
            "sourceId": "quote-1",
            "fileFormat": "xlsx",
            "allowedFields": ["supplierName", "quoteAmount", "productDescription"],
            "rows": [
                ["询比价记录表", "", ""],
                ["公司名", "价格", "备注"],
                ["某供应商A", "95000", "办公设备"],
            ],
        },
    )

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["operation"] == "PROCUREMENT_HEADER_MAPPING"
    assert data["headerRowNumber"] == 2
    assert data["mappings"][0]["standardField"] == "supplierName"
    assert data["mappings"][2]["standardField"] is None
    assert gateway.operations[0].rows[0] == {
        "rowNumber": 1,
        "cells": ["询比价记录表", "", ""],
    }


def test_procurement_header_mapping_accepts_java_preview_row_shape() -> None:
    gateway = HeaderMappingGateway()
    client = TestClient(create_app(settings=Settings(), model_gateway=gateway))

    response = client.post(
        "/ai/v1/agent/procurement-header-mapping",
        json={
            "sourceId": "quote-2",
            "fileFormat": "XLSX_OR_XLS",
            "allowedFields": ["SUPPLIER_NAME", "QUOTE_AMOUNT"],
            "rows": [
                {"rowNumber": 4, "cells": ["公司名称", "金额"]},
                {"rowNumber": 5, "cells": ["供应商A", "100"]},
            ],
        },
    )

    assert response.status_code == 200
    assert gateway.operations[0].rows[0]["rowNumber"] == 4
    assert gateway.operations[0].rows[0]["cells"] == ["公司名称", "金额"]
