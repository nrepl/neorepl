# Changelog

## main (unreleased)

### Features

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
