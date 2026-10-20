# Changelog

## main (unreleased)

### Features

- [#17](https://github.com/nrepl/neorepl/pull/17): `,trace` and `neorepl trace` trace functions, or whole namespaces, with cider-nrepl, and the REPL shows calls from other threads too.
- [#18](https://github.com/nrepl/neorepl/pull/18): `,ns` switches namespaces, loading the one named if it isn't yet, and lists the project's loaded namespaces without a name.
- [#23](https://github.com/nrepl/neorepl/pull/23): `,sessions` lists the named sessions and switches the REPL into one, such as an agent's, with `,sessions -` going back and the session's name in the prompt.
- [#24](https://github.com/nrepl/neorepl/pull/24): `d` in the full-screen inspector, and `neorepl inspect --def NAME`, define what's being inspected as a var in the session's namespace.
- [#28](https://github.com/nrepl/neorepl/pull/28): `--session` has the CLI's lookups, and `session` the MCP tools', look things up as a session sees them, in its language and namespace.
- [#32](https://github.com/nrepl/neorepl/pull/32): C-x C-s in the REPL shows the source of what's at the cursor, and C-x C-a the vars whose names have it in them, as rebel-readline does.

### Changes

- [#26](https://github.com/nrepl/neorepl/pull/26): The MCP tools use the session called `agent` unless a call names another, the one the skill has agents use with the CLI, rather than `default`.
- [#26](https://github.com/nrepl/neorepl/pull/26): `neorepl mcp --max-output` cuts what an evaluation prints and returns as a whole, not only each value and stream.
- [#29](https://github.com/nrepl/neorepl/pull/29): Test failures show their files relative to the directory neorepl runs in, when they're in it.
- [#34](https://github.com/nrepl/neorepl/pull/34): The help fits in 80 columns, the options' descriptions wrapping under one another.
- [#34](https://github.com/nrepl/neorepl/pull/34): `neorepl complete` exits 1 when nothing completes, saying so, or with a `not-found` result in edn and json rather than an empty list, as `apropos` does when nothing matches.

### Bug fixes

- [#20](https://github.com/nrepl/neorepl/pull/20): Interrupting an evaluation the moment it's sent, with Ctrl-C, `--timeout` or an MCP cancellation, no longer fails because the server hasn't started running it yet.
- [#21](https://github.com/nrepl/neorepl/pull/21): The REPL no longer shows what an interrupted evaluation prints or returns after the next one starts, like the report of a `,test` run that clojure.test finished despite the Ctrl-C.
- [#25](https://github.com/nrepl/neorepl/pull/25): `--timeout`, Ctrl-C and MCP cancellations stop the forms after the one they interrupt, which used to run anyway, and piggieback evaluates every form of an eval, rather than only the first, since the forms now go to the server one at a time.
- [#25](https://github.com/nrepl/neorepl/pull/25): A ClojureScript exception on shadow-cljs fails the evaluation, with exit status 1 and an MCP error, instead of passing as the value `:repl/exception!`.
- [#25](https://github.com/nrepl/neorepl/pull/25): A read from `*in*` no longer keeps `neorepl eval` waiting past `--timeout`.
- [#25](https://github.com/nrepl/neorepl/pull/25): Output on stderr that doesn't end its line no longer runs into the value or the prompt after it.
- [#26](https://github.com/nrepl/neorepl/pull/26): The MCP `pst` and `apropos` tools return an object as structured content, which the official MCP SDK insists on, rather than a list.
- [#26](https://github.com/nrepl/neorepl/pull/26): Code that reads `*in*` through the MCP tools gets end of input, instead of the next message the agent sends.
- [#26](https://github.com/nrepl/neorepl/pull/26): The MCP `reload` tool gives up once its time is up, so a namespace that never finishes loading can't keep the call, or `neorepl mcp` ending, waiting for good.
- [#26](https://github.com/nrepl/neorepl/pull/26): `neorepl mcp` checks a call's arguments against its tool's schema, and answers a line it can't read with a parse error, where an id past a long, for one, used to end it.
- [#26](https://github.com/nrepl/neorepl/pull/26): `neorepl mcp` reconnects on the first call after its nREPL server restarts, which used to fail.
- [#26](https://github.com/nrepl/neorepl/pull/26): The MCP `ports` tool says there are no port files, rather than failing.
- [#27](https://github.com/nrepl/neorepl/pull/27): Connecting to a host that doesn't answer gives up after 10 seconds, rather than after a minute or more.
- [#27](https://github.com/nrepl/neorepl/pull/27): An evaluation on Linux no longer waits 40ms on the server's socket, nor 40ms a form for several, with neorepl on the JVM.
- [#28](https://github.com/nrepl/neorepl/pull/28): `source`, and `doc` and `macroexpand` without cider-nrepl, work from a namespace that doesn't refer `clojure.core`, like one `in-ns` made.
- [#28](https://github.com/nrepl/neorepl/pull/28): `doc`, and the REPL's arglists, cover Java methods, fields and classes with cider-nrepl, with their Javadoc as text, and `doc` of a namespace names it once.
- [#28](https://github.com/nrepl/neorepl/pull/28): `source` reads a definition from where cider-nrepl or nREPL say it is, so it finds those in a file loaded from outside the classpath, and in ClojureScript.
- [#28](https://github.com/nrepl/neorepl/pull/28): `,macroexpand` with cider-nrepl no longer replaces the session's `*1`.
- [#29](https://github.com/nrepl/neorepl/pull/29): A whole-project `neorepl test` with cider-nrepl says which of the project's namespaces don't load, where it used to wait for good, and a test namespace that doesn't load says so, every run.
- [#29](https://github.com/nrepl/neorepl/pull/29): A test error's location is its test's, rather than the file of the frame that threw with the test's line, and on babashka every failure's is.
- [#29](https://github.com/nrepl/neorepl/pull/29): `neorepl test --timeout` covers loading the tests too, and running out of time there exits 124, rather than 3.
- [#29](https://github.com/nrepl/neorepl/pull/29): `neorepl test` of a namespace without tests says so and exits 1, rather than passing.
- [#30](https://github.com/nrepl/neorepl/pull/30): Symbols that start with a dash, like `->` and `-main`, are arguments to `doc`, `where` and the other lookups, rather than unknown options.
- [#30](https://github.com/nrepl/neorepl/pull/30): `--format edn` and `json` have failures too, like no server to talk to or a usage mistake, as their status and message, where stdout used to be empty.
- [#30](https://github.com/nrepl/neorepl/pull/30): A named session the server lost, as when it restarts, says so: where a new one takes its place, on stderr or in the MCP result, and where it doesn't, like `pst` and `sessions close`.
- [#30](https://github.com/nrepl/neorepl/pull/30): A timeout while an evaluation from before still runs in the session says so, rather than that interrupting failed.
- [#30](https://github.com/nrepl/neorepl/pull/30): `eval -f`, `load` and the MCP `load` tool check a file's delimiters first, saying where they don't balance rather than the reader's `REPL:9:1`, and say when a file can't be read.
- [#30](https://github.com/nrepl/neorepl/pull/30): A namespace that isn't there, a regex that doesn't parse and inspected code that throws get a message saying so, rather than the server's exception.
- [#31](https://github.com/nrepl/neorepl/pull/31): `pst` and `,stacktrace` show the frames that matter on every server: without cider-nrepl, not the REPL's own, and with it and babashka, not hiding a REPL's functions as duplicates, nor showing babashka's cider-nrepl frames.
- [#31](https://github.com/nrepl/neorepl/pull/31): `reload` recovers from a namespace whose file is gone, which cider-nrepl kept trying to reload until it restarted.
- [#31](https://github.com/nrepl/neorepl/pull/31): `,trace` and `neorepl trace` won't trace what the server's own tools call, like `clojure.core/map`, which took the server down.
- [#32](https://github.com/nrepl/neorepl/pull/32): What another thread prints without ending its line shows up above the prompt, rather than once the line is done or stuck to the start of the next value.
- [#32](https://github.com/nrepl/neorepl/pull/32): Ctrl-R and Ctrl-S show the history search's prompt, which eldoc's tip used to hide.
- [#32](https://github.com/nrepl/neorepl/pull/32): `,stacktrace` stays open on a frame it can't open, saying why, and comes back after the editor exits, rather than leaving for the prompt either way.
- [#32](https://github.com/nrepl/neorepl/pull/32): `,edit`, and opening a frame in `,stacktrace`, open a definition in a jar as a copy, rather than saying they can't.
- [#32](https://github.com/nrepl/neorepl/pull/32): What's typed while an evaluation or a command like `,reload` runs shows up at the next prompt only, rather than in its output too.
- [#32](https://github.com/nrepl/neorepl/pull/32): The REPL without a line editor leaves out the inspector's `[N]` marks, which only the full-screen inspector has a use for.
- [#32](https://github.com/nrepl/neorepl/pull/32): The REPL without a line editor says what a Ctrl-C did on the line after the `^C` the terminal shows, rather than right after it.
- [#32](https://github.com/nrepl/neorepl/pull/32): The arglists eldoc shows after the cursor get cut to fit the line, rather than wrapping onto the lines below it.
- [#32](https://github.com/nrepl/neorepl/pull/32): `NO_COLOR` takes the color out of the completion menu too.
- [#33](https://github.com/nrepl/neorepl/pull/33): Starting a server says when the build tool it takes isn't installed, rather than passing on the shell's `exec: lein: not found`.
- [#33](https://github.com/nrepl/neorepl/pull/33): A port file pointing at something that isn't an nREPL server gets given up on after 3 seconds, rather than 10.
- [#33](https://github.com/nrepl/neorepl/pull/33): The REPL's `--tool` connects only to a server that tool started, going by its port file, rather than to whatever server is up, and `neorepl server` starts a shadow-cljs server next to a Clojure one, and the other way around.
- [#33](https://github.com/nrepl/neorepl/pull/33): A server neorepl started that dies says how it ended, and takes the port file it leaves behind with it, rather than leaving it to point at nothing.
- [#33](https://github.com/nrepl/neorepl/pull/33): Ctrl-C while a server starts no longer has neorepl say the server ended before it started.
- [#33](https://github.com/nrepl/neorepl/pull/33): The REPL in a project inside another one says when it's using the other project's server, its own having none, which it used to without a word.

## 0.3.0 (2026-10-17)

Coding agents.

### Features

- [#16](https://github.com/nrepl/neorepl/pull/16): `doc/agents.md` says how to set up the skill and `neorepl mcp` with Claude Code, Codex, Gemini CLI, Cursor and VS Code.
- [#14](https://github.com/nrepl/neorepl/pull/14): A skill, `skills/neorepl/SKILL.md`, teaches coding agents to check, load, test and debug Clojure code with the CLI.
- [#13](https://github.com/nrepl/neorepl/pull/13): `neorepl check --fix`, and the MCP `check` tool's `fix`, repair unbalanced delimiters from the indentation, as parinfer's indent mode does.
- [#12](https://github.com/nrepl/neorepl/pull/12): `neorepl mcp` serves MCP on stdio for coding agents, with a curated set of tools that share the CLI's named sessions.

## 0.2.0 (2026-10-16)

Tests and inspection.

### Features

- [#11](https://github.com/nrepl/neorepl/pull/11): `,stacktrace` inspects the selected cause, or its ex-data, with cider-nrepl.
- [#10](https://github.com/nrepl/neorepl/pull/10): `,inspect` pages big collections a screenful at a time.
- [#9](https://github.com/nrepl/neorepl/pull/9): `,stacktrace` in the REPL shows the errors of the last test run, until an evaluation fails.
- [#8](https://github.com/nrepl/neorepl/pull/8): `neorepl test` and `,test` run ClojureScript tests in a session in ClojureScript, through cider-nrepl and piggieback.
- [#7](https://github.com/nrepl/neorepl/pull/7): `neorepl test` and `,test` say which namespace they're testing as the tests run, with cider-nrepl 0.63 or later.
- [#6](https://github.com/nrepl/neorepl/pull/6): `neorepl test` and `,test` show the stack trace of each test error.
- [#5](https://github.com/nrepl/neorepl/pull/5): `,stacktrace` in the REPL shows the last error full-screen and opens the source of the frame you pick in your `$EDITOR`.
- [#4](https://github.com/nrepl/neorepl/pull/4): `neorepl inspect`, and `,inspect` in the REPL, show values in cider-nrepl or babashka's inspector, full-screen in the REPL.
- [#3](https://github.com/nrepl/neorepl/pull/3): `neorepl test` and `neorepl retest`, and `,test` and `,retest` in the REPL, run tests through cider-nrepl or babashka's server, or plain `clojure.test` without either.

## 0.1.0 (2026-10-10)

Initial release.

### Features

- An interactive REPL with a line editor: multi-line input, highlighting, completion, the arglists of the function you're calling after the cursor, docs on C-x C-d, pretty printed values, history per project and Ctrl-C that interrupts the evaluation.
- REPL commands the CIDER way: `,doc`, `,source`, `,apropos`, `,macroexpand`, `,pst`, `,where`, `,edit`, `,reload` and `,help`.
- Jack-in: with no server to connect to, the REPL starts one for the project with cider-nrepl, through Leiningen, the Clojure CLI, babashka or shadow-cljs, and stops it when it ends. `neorepl server` starts one that stays up.
- ClojureScript REPLs through shadow-cljs and piggieback, which jack-in adds to deps.edn and Leiningen projects.
- `neorepl eval` and `neorepl load`, with sessions that carry `*ns*`, `*1` and `*e` from one call to the next, `--format edn` and `json`, `--timeout` and `--max-output`.
- `neorepl doc`, `source`, `apropos`, `complete`, `macroexpand`, `where`, `reload`, `pst`, `interrupt`, `sessions` and `ports`.
- `neorepl check`, which finds unbalanced delimiters without a server.
- Runs on the JVM (Java 17+, Clojure 1.10+) and on babashka 1.13.225+.
