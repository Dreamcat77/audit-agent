"""A real TCP disconnect must stop computation and remove only its own temp input."""
import socket
import threading
import time
from pathlib import Path

import uvicorn
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.ocr.cancellation import check_cancelled
from audit_ai.ocr.port import OcrExtraction, OcrPage


class CooperativeNativePort:
    def __init__(self):
        self.entered=threading.Event();self.stopped=threading.Event()
        self.release=threading.Event()
    def extract(self,path):
        if path.read_bytes()==b'next':
            return OcrExtraction('next',1.,(OcrPage(1,'next',1.),),'SYNTHETIC',1)
        self.entered.set()
        try:
            while not self.release.is_set():
                check_cancelled()
                time.sleep(.01)
        finally:self.stopped.set()


def test_real_disconnect_reaches_worker_and_next_upload_is_unaffected(tmp_path):
    port=CooperativeNativePort()
    app=create_app(Settings(ocr_temp_root=str(tmp_path)),ocr_port=port)
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(16)
    address=listener.getsockname()
    server=uvicorn.Server(uvicorn.Config(app,log_level='critical',lifespan='off'))
    thread=threading.Thread(target=lambda:server.run(sockets=[listener]),daemon=True);thread.start()
    try:
        deadline=time.monotonic()+5
        while not server.started:
            assert time.monotonic()<deadline
            time.sleep(.01)
        boundary='synthetic-boundary'
        body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="scan.png"\r\nContent-Type: image/png\r\n\r\nwait\r\n--{boundary}--\r\n').encode()
        client=socket.create_connection(address,timeout=3)
        client.sendall((f'POST /ai/v1/ocr/extract HTTP/1.1\r\nHost: local\r\nContent-Type: multipart/form-data; boundary={boundary}\r\nContent-Length: {len(body)}\r\n\r\n').encode()+body)
        assert port.entered.wait(3)
        client.shutdown(socket.SHUT_RDWR);client.close()
        assert port.stopped.wait(3),'disconnect did not cancel the worker'
        deadline=time.monotonic()+3
        while list(tmp_path.iterdir()) and time.monotonic()<deadline:time.sleep(.01)
        assert list(tmp_path.iterdir())==[]
        import httpx
        response=httpx.post(f'http://127.0.0.1:{address[1]}/ai/v1/ocr/extract',files={'file':('next.png',b'next','image/png')},trust_env=False)
        assert response.status_code==200 and response.json()['data']['text']=='next'
    finally:
        port.release.set()
        server.should_exit=True;thread.join(5);listener.close()
        assert not thread.is_alive()
