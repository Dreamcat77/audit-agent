"""Whole-answer formatting compatibility without JSON repair or content logging."""
import json
import httpx
import pytest
from audit_ai.config import Settings
from audit_ai.model.gateway import OpenAiCompatibleModelGateway, ModelGatewayFailure


def gateway(content, finish="stop"):
    return OpenAiCompatibleModelGateway(Settings(model_base_url="https://synthetic.invalid/v1",
        model_api_key="PRIVATE_KEY_CANARY", model_name="synthetic"),
        transport=httpx.MockTransport(lambda _: httpx.Response(200,json={"choices":[{
            "finish_reason":finish,"message":{"content":content}}]})))


@pytest.mark.parametrize("wrapper", ["{}", "```json\n{}\n```", " ```JSON\r\n{}\r\n```\n", "```\n{}\n```"])
def test_whole_valid_object_survives_presentation_wrapper_without_extra_call(wrapper, caplog):
    value={"facts":[{"type":"TOTAL_AMOUNT","value":"182320元"}],"note":"CONTENT_PRIVATE_CANARY"}
    result=gateway(wrapper.format(json.dumps(value))).analyze_contract_case("SOURCE_PRIVATE_CANARY","synthetic-request","JSON")
    assert result.payload==value and result.finish_reason=="STOP"
    assert "CONTENT_PRIVATE_CANARY" not in caplog.text
    assert "SOURCE_PRIVATE_CANARY" not in caplog.text and "PRIVATE_KEY_CANARY" not in caplog.text


@pytest.mark.parametrize("content", [
    '说明\n```json\n{}\n```', '```json\n{}\n```\n额外说明',
    '```json\n{}\n```\n```json\n{}\n```', '```json\n{"facts":[\n```',
    '```python\n{}\n```', '```json\n[]\n```', '```json\nnull\n```',
    '```json\n{} {}\n```', '```json\n{"secret":"CONTENT_PRIVATE_CANARY",}\n```',
])
def test_prose_multiple_objects_truncation_and_invalid_shapes_still_reject(content, caplog):
    with pytest.raises(ModelGatewayFailure):
        gateway(content).analyze_contract_case("source","synthetic-request","JSON")
    assert "CONTENT_PRIVATE_CANARY" not in caplog.text


def test_fenced_valid_json_with_length_does_not_become_complete():
    result=gateway('```json\n{}\n```',finish="length").analyze_contract_case("source","synthetic-request","JSON")
    assert result.finish_reason=="LENGTH"
