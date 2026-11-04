"""Launch an isolated local backend and a real HTTP/browser regression; secrets remain in process memory."""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.request


def main():
    root = Path(__file__).resolve().parents[1]
    backend = Path(sys.argv[1]).resolve()
    java_home = Path(os.environ['JAVA_HOME'])
    java = str(java_home / 'bin/java.exe')
    output = root / '.runtime/budget-batch-history-browser-20261027'
    output.mkdir(parents=True, exist_ok=True)
    deps = backend / 'target/batch-history-classpath.txt'
    subprocess.run([str(backend / 'mvnw.cmd'), '-q', 'dependency:build-classpath',
                    '-Dmdep.outputFile=' + str(deps)], cwd=backend, check=True, shell=True)
    classpath = str(backend / 'target/classes') + os.pathsep + deps.read_text().strip()
    password = secrets.token_urlsafe(24)
    encoded = subprocess.run([java, '-cp', classpath, 'com.fuquan.audit.security.tools.PasswordHashTool', '--stdin'],
                             input=password + '\n', text=True, capture_output=True, check=True).stdout.strip()
    assert encoded.startswith('{bcrypt}')
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    env = {key: value for key, value in os.environ.items() if not key.startswith('AUDIT_')}
    env.update(SPRING_PROFILES_ACTIVE='local', AUDIT_LOCAL_ACCOUNT_USERNAME='synthetic_batch_history',
               AUDIT_LOCAL_ACCOUNT_PASSWORD_HASH=encoded, AUDIT_LOCAL_ACCOUNT_REAL_NAME='批次回归测试',
               AUDIT_LOCAL_ACCOUNT_ROLES='SYSTEM_ADMIN', AUDIT_FILE_STORAGE_ROOT=str(output / 'storage'),
               AUDIT_BUDGET_MATERIAL_WORKER_ENABLED='true')
    base = f'http://127.0.0.1:{port}'
    with (output / 'backend.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen([java, '-cp', classpath, 'com.fuquan.audit.AuditApplication',
                                    '--server.address=127.0.0.1', f'--server.port={port}'],
                                   cwd=backend, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError('isolated backend exited; inspect local backend.log')
                try:
                    with urllib.request.urlopen(base + '/api/v1/health', timeout=2) as response:
                        assert json.load(response)['data']['status'] == 'UP'
                    break
                except OSError:
                    assert time.monotonic() < deadline, 'isolated backend startup deadline'
                    time.sleep(0.5)
            print('LOCAL_BACKEND_HEALTH=UP', flush=True)
            executable = sys.argv[2] if len(sys.argv) > 2 else r'C:\Program Files\Google\Chrome\Application\chrome.exe'
            result = subprocess.run(['node', str(root / 'tools/test-budget-batch-history.mjs'), executable,
                                     str(root / 'frontend/dist'), base, str(output)], cwd=root,
                                    input=json.dumps({'username': 'synthetic_batch_history', 'password': password}),
                                    text=True, check=False)
            if result.returncode:
                raise RuntimeError('local browser regression failed')
        finally:
            process.terminate()
            process.wait(timeout=30)


if __name__ == '__main__':
    main()
