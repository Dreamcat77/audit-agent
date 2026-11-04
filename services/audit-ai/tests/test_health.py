from fastapi.testclient import TestClient

from audit_ai.main import create_app


def test_health_does_not_expose_configuration() -> None:
    client = TestClient(create_app())

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "audit-ai"}


def test_request_id_is_preserved_and_returned() -> None:
    client = TestClient(create_app())

    response = client.get("/health", headers={"X-Request-Id": "req-demo-123"})

    assert response.headers["X-Request-Id"] == "req-demo-123"
