from audit_ai.ocr.paddle_adapter import money_transcription_suspect


def test_suspicious_uppercase_currency_gets_accuracy_pass_without_rewriting():
    assert money_transcription_suspect("金额（大写）：壹佰玖拾集万贰仟陆佰陆拾元整")
    assert not money_transcription_suspect("金额（大写）：壹佰玖拾柒万贰仟陆佰陆拾元整")
    assert not money_transcription_suspect("金额（大写）：佰拾万仟佰拾元角分")
    assert not money_transcription_suspect("本文介绍大写字母，非金额内容")
