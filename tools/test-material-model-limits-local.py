"""Local socket HTTP smoke with a deliberately input-limited synthetic provider.

Run with Python 3.11 and PYTHONPATH=services/audit-ai/src. No external model,
database, customer documents or production environment settings are used.
"""
import json
import logging
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import socket
import subprocess
import sys
import threading
import time

import httpx
import uvicorn
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import OpenAiCompatibleModelGateway


counts={'rejected':0,'accepted':0}


class Provider(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        segments=json.loads(body['messages'][1]['content'].split('segments=',1)[1])
        if sum(len(s['text']) for s in segments)>750:
            counts['rejected']+=1
            data={'success':False,'error':{'message':'maximum context length exceeded'}};status=400
        else:
            counts['accepted']+=1;rows=[]
            kind='subjectName' if 'businessType=BUDGET' in body['messages'][1]['content'] else 'requirementDescription'
            for s in segments:
                text=s.get('contextBefore','')+s['text']+s.get('contextAfter','')
                for found in re.finditer(r'仿真字段[0-9]+',text):
                    rows.append({'rowKey':found[0],'facts':[{'field':kind,'value':found[0],
                                 'excerpt':found[0],'locator':s['locator']}]})
            data={'choices':[{'finish_reason':'stop','message':{'content':json.dumps({'rows':rows},ensure_ascii=False)}}]};status=200
        raw=json.dumps(data,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)


class LoopbackTransport(httpx.BaseTransport):
    """Real socket requests without repeatedly loading the Windows TLS trust store.

    This test targets a plain HTTP loopback server and never uses external TLS.
    Production gateway construction remains unchanged.
    """
    def handle_request(self,request):
        assert request.url.scheme=='http' and request.url.host=='127.0.0.1'
        connection=HTTPConnection(request.url.host,request.url.port,timeout=10)
        try:
            connection.request(request.method,request.url.raw_path.decode(),body=request.content,headers=dict(request.headers))
            response=connection.getresponse()
            return httpx.Response(response.status,headers=response.getheaders(),content=response.read(),request=request)
        finally:connection.close()


def main():
    logging.getLogger('audit_ai').setLevel(logging.CRITICAL)
    provider=ThreadingHTTPServer(('127.0.0.1',0),Provider)
    provider_thread=threading.Thread(target=provider.serve_forever,daemon=True);provider_thread.start()
    settings=Settings(model_base_url=f'http://127.0.0.1:{provider.server_port}/v1',
                      model_name='synthetic-only',model_api_key='synthetic-only')
    sock=socket.socket();sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    gateway=OpenAiCompatibleModelGateway(settings,transport=LoopbackTransport())
    app=create_app(settings,model_gateway=gateway)
    logging.getLogger('audit_ai').setLevel(logging.CRITICAL)
    server=uvicorn.Server(uvicorn.Config(app,log_level='critical',access_log=False))
    worker=threading.Thread(target=lambda:server.run(sockets=[sock]),daemon=True);worker.start()
    base=f'http://127.0.0.1:{port}';deadline=time.monotonic()+20
    try:
        while not server.started:
            if not worker.is_alive() or time.monotonic()>deadline:raise RuntimeError('local startup failed')
            time.sleep(.05)
        curl='curl.exe' if sys.platform=='win32' else 'curl'
        health=subprocess.run([curl,'--fail','--silent','--show-error',base+'/health'],capture_output=True,text=True,check=True)
        assert json.loads(health.stdout)['status']=='UP'
        print('CURL_HEALTH='+health.stdout,flush=True)
        sizes=[200,1500,3000,6000,10000,16000,24000,36000,48000,60000,72000,96000]
        with httpx.Client(timeout=300) as client:
            for kind in ('BUDGET','PROCUREMENT'):
                for index,size in enumerate(sizes):
                    # Exact size variation, paragraph boundaries, and tail evidence.
                    text='仿真字段0\n'+('办公材料说明。'*((size//7)+1))[:size//2]+'\n仿真字段1\n'
                    text+=('补充材料说明。'*((size//7)+1))[:size//2]+'\n仿真字段2'
                    segments=[{'locator':f'page:{i//6000+1}','text':text[i:i+6000]} for i in range(0,len(text),6000)]
                    body={'businessType':kind,'materialId':f'smoke-{kind}-{index}','sha256':f'{index:064x}','segments':segments}
                    response=client.post(base+'/ai/v1/materials/extract-fields',json=body);response.raise_for_status()
                    facts=[f for row in response.json()['data']['rows'] for f in row['facts']]
                    assert {f['value'] for f in facts}=={'仿真字段0','仿真字段1','仿真字段2'}
                    assert len(facts)==3,'Boundary context must not duplicate a fact'
                    print(json.dumps({'kind':kind,'case':index+1,'inputChars':len(text),'http':response.status_code,
                                      'headMiddleTail':True,'result':'PASS'}),flush=True)
        assert counts['rejected']>0
        print('LOCAL_SOCKET_HTTP_24_MATERIALS=PASS '+json.dumps(counts),flush=True)
    finally:
        server.should_exit=True;worker.join(timeout=10)
        provider.shutdown();provider.server_close();provider_thread.join(timeout=5);sock.close()


if __name__=='__main__':main()
