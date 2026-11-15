"""Records a tape as an asciicast v2 file, for asciinema-player.

    python3 -I record.py TAPE OUT.cast [COMMAND...]

It runs COMMAND (by default an interactive bash without its startup files)
in a pseudo-terminal of WIDTH by HEIGHT and types what the tape says,
writing everything the terminal shows, with wall-clock times, to OUT.cast.
The command gets this process's environment, with TERM=xterm-256color.
record-all.sh sets up the rest.

A tape has one step per line, and # starts a comment:

    Type "(+ 1 2)"            typed a key at a time, like a person would
    Enter                     also Tab, Backspace, Escape, Space, the arrows
                              (Up, Down, Left, Right), Home, End, PageUp,
                              PageDown, and chords like Ctrl+X, several to a
                              line: Ctrl+X Ctrl+D
    Sleep 1.5                 seconds
    Wait /user=> $/           until what the screen got since the last
                              step matches, as text, without the escapes
                              and carriage returns, ^ and $ for each line
    Shell "sed -i '' ..."     runs outside the terminal, from this
                              process's working directory

The cast's header has an idle_time_limit of 2, so the player squeezes the
waits for the server into two seconds. When a Wait times out, what got
recorded till then goes to OUT.cast.failed, to see what went wrong.
"""

import ast
import codecs
import fcntl
import json
import os
import pty
import random
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import threading
import time

KEYS = {
    "Enter": "\r", "Tab": "\t", "Backspace": "\x7f", "Escape": "\x1b",
    "Space": " ", "PageUp": "\x1b[5~", "PageDown": "\x1b[6~",
}

# The cursor keys, which a terminal sends as ESC O and the letter once the
# program asks for its application mode (ESC [ ? 1 h), as JLine does, and
# as ESC [ and the letter otherwise.
CURSOR_KEYS = {"Up": "A", "Down": "B", "Right": "C", "Left": "D", "Home": "H", "End": "F"}
CURSOR_MODE = re.compile(r"\x1b\[\?1([hl])")

# CSI and OSC sequences, character set ones like ESC ( 0, which draws the
# trace's lines, and two-byte escapes like keypad mode's, for Wait.
ESCAPES = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()*+].|\x1b[^\[\]]")

WAIT_TIMEOUT = 60

# 88 columns rather than 80, since neorepl's banner on connecting takes 82
# to 84 with the server's address and version.
WIDTH, HEIGHT = 88, 24


def parse(path):
    """The tape's steps, as (name, argument) pairs."""
    steps = []
    with open(path) as f:
        for n, line in enumerate(f, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, _, arg = line.partition(" ")
            arg = arg.strip()
            if name in ("Type", "Shell"):
                steps.append((name, ast.literal_eval(arg)))
            elif name == "Sleep":
                steps.append((name, float(arg)))
            elif name == "Wait":
                if not (arg.startswith("/") and arg.endswith("/")):
                    sys.exit(f"{path}:{n}: Wait takes /a regex/")
                steps.append((name, re.compile(arg[1:-1], re.M)))
            else:
                keys = []
                for key in line.split():
                    if key.startswith("Ctrl+") and len(key) == 6:
                        keys.append(chr(ord(key[5].lower()) & 0x1F))
                    elif key in KEYS or key in CURSOR_KEYS:
                        keys.append(key)
                    else:
                        sys.exit(f"{path}:{n}: don't know {key}")
                steps.append(("Keys", keys))
    return steps


class Terminal:
    """The command in a pseudo-terminal, with what it printed, as events,
    and as text since the last step, for Wait."""

    def __init__(self, command, width, height):
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            fcntl.ioctl(0, termios.TIOCSWINSZ, struct.pack("HHHH", height, width, 0, 0))
            os.environ["TERM"] = "xterm-256color"
            os.execvp(command[0], command)
        self.start = time.monotonic()
        self.events = []
        self.text = ""
        self.application_cursor = False
        self.lock = threading.Lock()
        self.decoder = codecs.getincrementaldecoder("utf-8")("replace")
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        while True:
            try:
                ready, _, _ = select.select([self.fd], [], [], 0.1)
                if not ready:
                    continue
                data = os.read(self.fd, 65536)
            except OSError:
                data = b""
            if not data:
                return
            out = self.decoder.decode(data)
            if out:
                with self.lock:
                    self.events.append([round(time.monotonic() - self.start, 6), "o", out])
                    self.text += ESCAPES.sub("", out).replace("\r", "")
                    for mode in CURSOR_MODE.findall(out):
                        self.application_cursor = mode == "h"

    def clear(self):
        """Starts what Wait looks at afresh, for the next step."""
        with self.lock:
            self.text = ""

    def send(self, text):
        os.write(self.fd, text.encode())

    def key(self, key):
        """What the terminal sends for key, a name or a character."""
        if key in CURSOR_KEYS:
            return ("\x1bO" if self.application_cursor else "\x1b[") + CURSOR_KEYS[key]
        return KEYS.get(key, key)

    def wait(self, pattern):
        """Waits for pattern, returning None, or else why it gave up."""
        deadline = time.monotonic() + WAIT_TIMEOUT
        while time.monotonic() < deadline:
            with self.lock:
                match = pattern.search(self.text)
                if match:
                    self.text = self.text[match.end():]
                    return
            time.sleep(0.05)
        with self.lock:
            seen = self.text[-500:]
        return f"Timed out waiting for /{pattern.pattern}/. The screen got:\n{seen}"

    def close(self):
        try:
            os.kill(self.pid, signal.SIGHUP)
        except ProcessLookupError:
            pass
        os.waitpid(self.pid, 0)
        self.reader.join(1)


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    tape, out = sys.argv[1], sys.argv[2]
    command = sys.argv[3:] or ["bash", "--noprofile", "--norc", "-i"]
    steps = parse(tape)
    typing = random.Random(os.path.basename(tape))
    term = Terminal(command, WIDTH, HEIGHT)
    failure = None
    try:
        for name, arg in steps:
            if name in ("Type", "Keys"):
                term.clear()
            if name == "Type":
                for char in arg:
                    term.send(char)
                    time.sleep(typing.uniform(0.035, 0.075))
            elif name == "Keys":
                term.send("".join(term.key(key) for key in arg))
                time.sleep(0.1)
            elif name == "Sleep":
                time.sleep(arg)
            elif name == "Wait":
                failure = term.wait(arg)
                if failure:
                    break
            elif name == "Shell":
                subprocess.run(arg, shell=True, check=True)
    finally:
        # The cast lasts till the tape's done, so a Sleep at the end holds
        # the last screen, and what hanging up prints isn't in it.
        end = round(time.monotonic() - term.start, 6)
        term.close()
        term.events = [e for e in term.events if e[0] <= end] + [[end, "o", ""]]
    header = {"version": 2, "width": WIDTH, "height": HEIGHT, "idle_time_limit": 2}
    with open(out + ".failed" if failure else out, "w") as f:
        f.write(json.dumps(header) + "\n")
        for event in term.events:
            f.write(json.dumps(event, ensure_ascii=False) + "\n")
    if failure:
        sys.exit(failure)


if __name__ == "__main__":
    main()
