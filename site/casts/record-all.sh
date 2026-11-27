#!/usr/bin/env bash
# Records the tapes here into ../static/casts, against a server jack-in
# starts in a copy of the demo project, with this checkout's neorepl:
#
#   site/casts/record-all.sh            every tape
#   site/casts/record-all.sh docs cli   just these
#
# neorepl runs on babashka 1.13.227 or newer: $BB, or the bb on the PATH.
# The server needs the Clojure CLI. The recordings get a home of their own,
# so neither your config nor your history shows up in them.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
out="$repo/site/static/casts"
bb=${BB:-$(command -v bb || true)}
[ -n "$bb" ] || { echo "neorepl needs babashka 1.13.227 or newer, on the PATH or in \$BB" >&2; exit 1; }

# Outside the repo, so a failed recording, which record.py leaves next to
# where the cast would go, doesn't end up on the site.
work=/tmp/neorepl-casts
demo="$work/coffee"
rm -rf "$work"
mkdir -p "$work/bin" "$work/home" "$demo"

cat > "$work/bin/neorepl" <<EOF
#!/bin/sh
exec "$bb" -cp "$repo/src" -m neorepl.main "\$@"
EOF
chmod +x "$work/bin/neorepl"
export PATH="$work/bin:$PATH"

restore() {
  rm -rf "$demo/src" "$demo/test"
  cp -R "$here/demo/." "$demo"
}

restore
cd "$demo"

# The server gets your environment, for the Clojure CLI's caches, and stops
# with the script, however it ends.
server=
trap '[ -n "$server" ] && kill "$server" 2> /dev/null; wait' EXIT
neorepl server > "$work/server.log" 2>&1 &
server=$!
until [ -s .nrepl-port ]; do
  kill -0 "$server" 2> /dev/null || { cat "$work/server.log"; exit 1; }
  sleep 0.5
done

# cider-nrepl loads the clojure.test extensions that report what an =
# expected and got with its test middleware, on the first test op, so a test
# namespace loaded before that reports the forms instead (cider-nrepl#1079).
# One test run goes first, so the namespaces the tapes reload report the
# values.
neorepl test demo.core-test > /dev/null || true

tapes=("$@")
if [ ${#tapes[@]} -eq 0 ]; then
  # The order the site shows them in, with cli last, where the test still
  # fails.
  tapes=(overview docs inspector errors hints debugger trace tests reload cli)
fi

for name in "${tapes[@]}"; do
  echo "Recording $name"
  restore
  # What the last tape changed goes back to how the project has it, and
  # nothing's profiled. unprofile fails when nothing is.
  neorepl reload > /dev/null
  neorepl unprofile demo.core > /dev/null 2>&1 || true
  neorepl profile --clear > /dev/null
  env -i \
    PATH="$work/bin:/usr/bin:/bin" \
    HOME="$work/home" \
    XDG_CONFIG_HOME="$work/home/.config" \
    XDG_STATE_HOME="$work/home/.local/state" \
    XDG_DATA_HOME="$work/home/.local/share" \
    XDG_CACHE_HOME="$work/home/.cache" \
    INPUTRC=/dev/null \
    HISTFILE=/dev/null \
    LANG=en_US.UTF-8 \
    PS1='$ ' \
    BASH_SILENCE_DEPRECATION_WARNING=1 \
    python3 -I "$here/record.py" "$here/$name.tape" "$work/$name.cast"
  cp "$work/$name.cast" "$out/$name.cast"
done
