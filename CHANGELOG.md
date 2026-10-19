# Changelog

## main (unreleased)

### Features

- [#17](https://github.com/nrepl/neorepl/pull/17): `,trace` and `neorepl trace` trace functions, or whole namespaces, with cider-nrepl, and the REPL shows calls from other threads too.
- [#18](https://github.com/nrepl/neorepl/pull/18): `,ns` switches namespaces, loading the one named if it isn't yet, and lists the project's loaded namespaces without a name.
- [#23](https://github.com/nrepl/neorepl/pull/23): `,sessions` lists the named sessions and switches the REPL into one, such as an agent's, with `,sessions -` going back and the session's name in the prompt.
- [#24](https://github.com/nrepl/neorepl/pull/24): `d` in the full-screen inspector, and `neorepl inspect --def NAME`, define what's being inspected as a var in the session's namespace.

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
