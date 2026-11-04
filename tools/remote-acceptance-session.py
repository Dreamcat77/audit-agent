"""Pinned, interactive maintenance session; credentials stay in process memory."""
import getpass
import json
from pathlib import Path
import shlex
import sys
import os

import paramiko


def main():
    # Maintenance requests may contain one-time credentials. Never echo input
    # JSON from the Windows PTY (getpass only protects the first password).
    if os.name == 'nt':
        import ctypes
        handle = ctypes.windll.kernel32.GetStdHandle(-10)
        mode = ctypes.c_ulong()
        if ctypes.windll.kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            ctypes.windll.kernel32.SetConsoleMode(handle, mode.value & ~0x0004)
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.load_host_keys(str(Path.home() / ".ssh" / "known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    password = getpass.getpass("SSH password (not saved): ")
    try:
        client.connect(sys.argv[1], username=sys.argv[2], password=password,
                       allow_agent=False, look_for_keys=False, timeout=15)
        print("REMOTE_SESSION_READY", flush=True)
        for line in sys.stdin:
            request = json.loads(line)
            if request.get("exit"):
                break
            if "put" in request:
                with client.open_sftp() as sftp:
                    for local, remote in request["put"]:
                        sftp.put(local, remote)
                print("REMOTE_UPLOAD_PASS", flush=True)
            if "command" in request:
                command = request["command"]
                if request.get("sudo"):
                    command = "sudo -S -p '' bash -c " + shlex.quote(command)
                stdin, stdout, stderr = client.exec_command(command, get_pty=False)
                stdout.channel.set_combine_stderr(True)
                if request.get("sudo"):
                    stdin.write(password + "\n")
                    if request.get("authPayload") is not None:
                        stdin.write(json.dumps(request["authPayload"], ensure_ascii=False) + "\n")
                    stdin.flush()
                elif request.get("authFromSshPassword"):
                    # Synthetic acceptance helpers consume credentials from stdin;
                    # never place the password in a command line or temp file.
                    stdin.write(json.dumps({
                        "username": request.get("authUsername", sys.argv[2]),
                        "password": password,
                    }) + "\n")
                    stdin.flush()
                elif request.get("authPayload") is not None:
                    # Forward one-time application credentials from memory only.
                    # The payload is never written to disk or put in the command line.
                    stdin.write(json.dumps(request["authPayload"], ensure_ascii=False) + "\n")
                    stdin.flush()
                stdin.channel.shutdown_write()
                for output in stdout:
                    print(output, end="", flush=True)
                print("REMOTE_EXIT=" + str(stdout.channel.recv_exit_status()), flush=True)
            if "get" in request:
                try:
                    with client.open_sftp() as sftp:
                        for remote, local in request["get"]:
                            sftp.get(remote, local)
                    print("REMOTE_DOWNLOAD_PASS", flush=True)
                except OSError as failure:
                    # Permission errors are not a reason to lose the authenticated
                    # maintenance channel. Never print the request or private path.
                    print("REMOTE_DOWNLOAD_FAILED type=" + type(failure).__name__, flush=True)
    finally:
        client.close()


if __name__ == "__main__":
    main()
