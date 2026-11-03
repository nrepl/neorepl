# Working on neorepl

This is for agents working on neorepl itself. To use neorepl from an agent,
see [doc/agents.md](doc/agents.md) and the skill in
[skills/neorepl/SKILL.md](skills/neorepl/SKILL.md).

## Where things are

[PLAN.md](PLAN.md) has the design, the milestones and, under "What we
verified", what real servers actually do, with a gotcha for every surprise.
Read the part that covers a feature before changing it, and add what you
find: the server versions you checked against, what they answered, and the
upstream issue if there is one. The open questions at the end track the
issues filed upstream.

`src/neorepl`:

- `main.clj`: the command line, every command and its help.
- `repl.clj`: the REPL, with the line editor on a terminal and plain lines
  elsewhere. `editor.cljc` is the line editor, and the only namespace that
  touches JLine.
- `nrepl.clj`: the client on top of `nrepl.core`'s transports, one reader
  thread per connection handing responses to the handler for their id.
- `evaluation.clj`: following an eval's responses, stdin and interrupts.
- `features.clj`: everything else asked of a server. `lookups.clj` is what
  the commands and the REPL share, `render.clj` turns it into text.
- `mcp.clj`: the MCP server, over the same features as the commands.
- `inspector.clj`, `stacktrace.clj`, `ns_browser.clj`, `spec_browser.clj` and
  `browser.clj`: the full-screen views.
- `lexer.clj`: tells finished forms from unfinished ones and classifies
  tokens. It doesn't read code, on purpose. `repair.clj` fixes delimiters
  from indentation.
- `jack_in.clj`, `discovery.clj`, `sessions.clj`, `settings.clj`,
  `shutdown.clj`, `cli.clj`, `json.clj`, `xdg.clj`: starting servers,
  finding them, named sessions, the REPL's settings and its config file,
  cleaning up on exit, and the small parts done without libraries.

## Running things

```shell
bb test                          # on babashka
bb test neorepl.repl-test        # just these namespaces
bb test:jvm                      # on the JVM, Clojure 1.12
bb test:jvm :1.10                # or :1.11
bb test:integration              # against real servers, on babashka
bb test:integration:jvm          # and on the JVM
clj-kondo --lint src test integration
```

Run both runtimes before pushing. CI runs the JVM on Java 17, 21 and 25 with
Clojure 1.10 to 1.12, and bb 1.13.227 and the latest, on Linux, macOS and
Windows.

The tests run against an in-process nREPL server from
`test/neorepl/test_server.clj`, with fakes for the cider-nrepl ops, and
`test/orchard/namespace.clj` stands in for orchard. On bb they start servers
with the `bb` on the PATH, so putting another build first on the PATH tests
that one. `skill-test` and `agents-doc-test` run every command the skill and
doc/agents.md show with `--help`, so renaming a command or option means
updating them too.

On bb, `SocketException` traces from `nREPL-session` show up in the test
output. They're [babashka#2227](https://github.com/babashka/babashka/issues/2227),
not a failure.

## Rules the code keeps to

- The same source runs on the JVM and on babashka 1.13.227+. That means no
  `gen-class` or AOT, `reify` only of interfaces bb has, and only the JLine
  classes bb exposes (PLAN.md lists them). Before using a class, check that
  the oldest supported bb has it. Where the runtimes differ, `.cljc` with
  `:bb` and `:clj` reader conditionals, as in `editor.cljc`.
- Clojure 1.10 APIs only, so no `parse-long`, `update-vals` and friends:
  in-process mode runs this code inside the project's JVM.
- The runtime dependencies are Clojure, nREPL and JLine, all of which bb
  ships. No new ones: CLI parsing and JSON are done by hand for that reason.
- The client is thin. A feature uses cider-nrepl's op when there is one,
  nREPL's built-in op next, and evaluating code last. Code neorepl evaluates
  runs outside the user's sessions, so `*1` and `*e` stay put, and qualifies
  everything (`clojure.core/require`), since the session can be in a
  namespace that doesn't refer `clojure.core`. ClojureScript needs reader
  conditionals there, as its `require` only works unqualified.
- Without a terminal there's no line editor and no ANSI, only plain text,
  edn or JSON.
- Windows: output ends lines with `\r\n` (the tests compare through `lf`),
  and there's no SIGTERM.
- Every behaviour change gets a test, on both runtimes.

## Real servers

The fakes behave like today's cider-nrepl, which says nothing about the next
one, so anything that depends on a server gets checked against real ones:
nREPL with cider-nrepl (`neorepl server` jacks one in), `bb nrepl-server`,
shadow-cljs, piggieback with Node, nbb and Basilisp. Write down the versions
in PLAN.md.

The integration tests in `integration/` do that for all of them, each in a
project of its own: nREPL with the cider-nrepl jack-in brings, started by
the Clojure CLI and by Leiningen, and the server of the `bb` on the PATH,
in `cljs_servers_test.clj`, ClojureScript sessions on Node, through
piggieback and shadow-cljs, and in `dialects_test.clj`, nbb's and
Basilisp's servers, which jack-in doesn't start, with nbb from npm and
Basilisp in a venv. `servers.clj` starts them. They need the Clojure CLI, Leiningen, Node
and npm, and Python 3. A feature that depends on what a server answers gets
a test there as well as against the fakes.

Before a release, run [proof](https://github.com/nrepl/proof) against
neorepl: `proof proxy` between neorepl and a server, and the `proof serve`
scenarios.

Some behaviour only shows on Linux, like TCP's delayed ACKs (the reason for
`quickack` in `nrepl.clj`) or a write to a closed socket succeeding once. On
macOS, check it in Docker:

```shell
docker run --rm --init -v "$PWD":/neorepl:ro -v /path/to/linux-bb:/bb \
  clojure:temurin-21-tools-deps bash -c '
    apt-get update -qq && apt-get install -y -qq procps >/dev/null
    cp -r /neorepl /tmp/neorepl && cd /tmp/neorepl && PATH=/bb:$PATH bb test'
```

The image has no bb, so mount a Linux build from bb's releases. `--init`
reaps the processes `stop-test` leaves behind, and procps has the `kill` the
jack-in tests use. Working on a copy keeps the container out of the checkout.

## Upstream

A good share of what neorepl works around is a server's bug. When a
workaround goes in, the issue goes in the code next to it and in PLAN.md, so
it can come out once a fixed release is the oldest one supported. Issues
follow the project's template, with the smallest reproduction there is, and
say they came up working on neorepl. babashka's tracker gets a few at a
time, grouped by theme, not a batch.

## Commits, changelog and releases

- One logical change per commit, with a subject saying why. `[Fix #N]` only
  when the commit fixes all of #N, `[#N]` otherwise. A commit never names
  its own PR.
- The CHANGELOG entry goes in the same commit as the change, under
  `main (unreleased)`, starting with a link to the PR:
  `- [#34](https://github.com/nrepl/neorepl/pull/34): The help fits in 80 columns.`
  Open the PR first, then amend the link in.
- PRs get rebase-merged, for a linear history.
- The help fits in 80 columns, which a test checks. Prose in docs,
  docstrings and comments doesn't use `--` as a dash.
- `neorepl.main/version` reads `X.Y.Z-snapshot` between releases. A release
  bumps it and adds the CHANGELOG section in a "Release X.Y.Z" commit, gets
  tagged `vX.Y.Z` with a GitHub release titled "neorepl X.Y.Z" that carries
  that section, and bumps the version to the next snapshot straight after.
