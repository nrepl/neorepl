# Changelog

## main (unreleased)

### Features

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
