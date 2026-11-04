"""Real rotated OCR/model with repeated HTTP health checks during native work."""
import concurrent.futures
import json
from pathlib import Path
import sys
import time
import requests


def main():
    base=sys.argv[1];path=Path(sys.argv[2]);durations=[]
    def ocr():
        with path.open('rb') as handle:
            response=requests.post(base+'/ai/v1/ocr/extract',files={'file':(path.name,handle,'image/jpeg')},timeout=900)
        response.raise_for_status();data=response.json()['data']
        assert '118000' in data['text'].replace(',',''),'QUOTE_NOT_RECOGNIZED'
        return data
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        future=pool.submit(ocr)
        while not future.done():
            start=time.monotonic()
            response=requests.get(base+'/health',timeout=2)
            response.raise_for_status();durations.append(time.monotonic()-start)
            assert durations[-1]<1,'HEALTH_BLOCKED_BY_NATIVE_OCR'
            time.sleep(.5)
        data=future.result()
    assert len(durations)>=5,'NOT_TESTED_DURING_OCR'
    print(json.dumps(dict(result='PASS',ocrChars=len(data['text']),healthChecks=len(durations),
        maxHealthMillis=round(max(durations)*1000)),ensure_ascii=False),flush=True)


if __name__=='__main__':main()
