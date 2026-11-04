"""Run an authorized synthetic acceptance command over pinned SSH, without saving credentials."""
import argparse
import getpass
from pathlib import Path
import shlex
import sys

import paramiko


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--put", nargs=2, action="append", default=[])
    parser.add_argument("--get", nargs=2, action="append", default=[])
    parser.add_argument("--sudo", action="store_true")
    parser.add_argument("command")
    args = parser.parse_args()
    password = getpass.getpass("SSH password (not saved): ")
    client = paramiko.SSHClient()
    client.load_system_host_keys()
    client.load_host_keys(str(Path.home() / ".ssh" / "known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    try:
        client.connect(args.host, username=args.user, password=password,
                       allow_agent=False, look_for_keys=False, timeout=15)
        if args.put:
            with client.open_sftp() as sftp:
                for local, remote in args.put:
                    sftp.put(local, remote)
        command = "sudo -S -p '' bash -c " + shlex.quote(args.command) if args.sudo else args.command
        stdin, stdout, stderr = client.exec_command(command, get_pty=False)
        if args.sudo:
            stdin.write(password + "\n")
            stdin.flush()
        password = None
        stdin.channel.shutdown_write()
        # Combine stderr into the drained stream; never leave a full error pipe.
        stdout.channel.set_combine_stderr(True)
        for line in stdout:
            print(line, end="", flush=True)
        result = stdout.channel.recv_exit_status()
        if args.get:
            with client.open_sftp() as sftp:
                for remote, local in args.get:
                    sftp.get(remote, local)
        return result
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
