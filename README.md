# neorepl

A terminal REPL for Clojure (and friends) built on nREPL. It's meant as a
spiritual successor to [REPLy](https://github.com/trptcolin/reply), with a
proper line editor, the core of CIDER's tooling and a CLI that works just as
well for scripts and coding agents as it does for people.

It's early days: there's a REPL, jack-in and a handful of command line
tools. [PLAN.md](PLAN.md) has the details on where this is going.

## Usage

[bbin](https://github.com/babashka/bbin) installs the latest release as a
`neorepl` command, which runs on [babashka](https://babashka.org) 1.13.225
or newer:

```shell
bbin install io.github.nrepl/neorepl
```

From a checkout, `bbin install .` installs that instead.

`neorepl` connects to the nREPL server whose `.nrepl-port` file is in the
current directory or one of its parents (`neorepl ports` lists the ones it
can see) and starts a REPL. Enter evaluates the input once its forms are
complete, Ctrl-C interrupts an evaluation and Ctrl-D exits. Code gets
highlighted, values get pretty printed and the history is kept per project.
TAB completes, the arglists of the function you're calling show up after
the cursor and C-x C-d shows the documentation of what's at the cursor.
Commands start with a comma, the CIDER way: `,doc`, `,source`, `,apropos`,
`,macroexpand`, `,pst` for the last error's stack trace, `,reload`,
`,test`, `,edit` to open a definition in your `$EDITOR`, and `,help` for the
rest.
Without a terminal, say in a pipe or Emacs' `M-x shell`, it works like
`clojure.main`'s REPL instead.

With no server to connect to, `neorepl` starts one for the project, the way
CIDER's jack-in does, and stops it when the REPL ends. The project's build
tool runs it, with cider-nrepl: Leiningen for a `project.clj`, the Clojure
CLI for a `deps.edn`, babashka, which has CIDER's essentials built in, for a
`bb.edn` and shadow-cljs for a `shadow-cljs.edn`. `--tool` picks another one
and `--aliases` adds deps.edn aliases or Leiningen profiles. `neorepl server`
starts one that stays up until you stop it, for editors, coding agents and
`neorepl eval` to share:

```shell
neorepl --aliases :dev:test
neorepl server --port 7888
```

ClojureScript REPLs run inside a Clojure session on the server, through
shadow-cljs or piggieback, which jack-in adds to deps.edn and Leiningen
projects. The REPL follows the session into whichever runtime you start:

```clojure
;; shadow-cljs: the :app build's runtime, or a fresh Node one
(shadow/repl :app)
(shadow/node-repl)
;; figwheel-main: builds dev and connects to its runtime
(figwheel.main.api/start "dev")
;; ClojureScript alone: a Node runtime
(require 'cljs.repl.node)
(cider.piggieback/cljs-repl (cljs.repl.node/repl-env))
```

From then on evaluations are ClojureScript's, and so are completion, eldoc,
`,doc`, `,where` and `,macroexpand` when the server has cider-nrepl.
`:cljs/quit` switches back. Ctrl-C can't stop ClojureScript code that's
still running. With shadow-cljs a second Ctrl-C stops waiting for it, and
piggieback stops waiting on the first Ctrl-C, though the code keeps the
runtime busy.

`neorepl eval` evaluates code on the same server:

```shell
neorepl eval '(+ 1 2)'
neorepl eval --format json -f script.clj
echo '(range 3)' | neorepl eval -
```

Evaluations share a session, so `*ns*`, `*1` and `*e` carry over from one
call to the next. `--session NAME` keeps separate ones apart, `neorepl
sessions` lists them and `neorepl sessions close NAME` starts one over.
Servers whose sessions end with the connection, like Basilisp's and jank's,
start each call afresh. `neorepl eval --help` has the rest.

`neorepl load` loads a file the way `load-file` does, which is how you get
your changes into the running program after editing it:

```shell
neorepl load src/my/app.clj
```

`neorepl doc`, `neorepl source`, `neorepl apropos` and `neorepl pst` do what
their `clojure.repl` namesakes do, `neorepl macroexpand` expands macros,
`neorepl complete` lists completions, `neorepl where` says where something's
defined and `neorepl reload` reloads code, all using cider-nrepl when the
server has it:

```shell
neorepl doc map
neorepl source --ns my.app my.app/start
neorepl pst --format json
```

`neorepl test` runs tests, in the namespaces you name, the tests you name or,
without either, the whole project's, and prints what didn't pass and where.
`neorepl retest` reruns the ones that didn't. They're `,test` and `,retest`
in the REPL. cider-nrepl or babashka on the server runs them, and so does
plain `clojure.test`, given the namespaces:

```shell
neorepl test
neorepl test my.app-test my.app.db-test
neorepl test --format json my.app-test/start-test
```

`neorepl check` finds unbalanced parens, brackets and braces, the most
common way for an edit (or a coding agent) to break a Clojure file. It
doesn't need a server:

```shell
$ neorepl check src/my/app.clj
src/my/app.clj:12:1: Unclosed (
```

## How does it compare?

neorepl isn't the only terminal REPL that talks nREPL these days. The
Clojure team's [clojure-cli.repl](https://github.com/clojure/clojure-cli.repl)
and babashka's `bb repl --connect` are both nREPL clients too, but they're
after different things. neorepl's goals are a CIDER-like REPL in the
terminal, a CLI that serves coding agents as well as people, and working
with any nREPL server out there. Here's how the three stack up in practice
(as of October 2026):

| | neorepl | clojure-cli.repl | `bb repl --connect` |
|---|---|---|---|
| Runs on | JVM or babashka | JVM, Clojure 1.12+ | babashka |
| Servers | any nREPL server | its own; other servers only evaluate | any nREPL server |
| Starts a server for you | Leiningen, deps.edn, bb and shadow-cljs projects, with cider-nrepl | deps.edn projects | no |
| Completion, arglists as you type | yes | no | yes |
| Docs | yes, and source, apropos, macroexpansion | yes, with Java docs | yes |
| Structural editing, eval at the cursor | no | yes | no |
| Inspector | coming | yes | no |
| Test runner | yes | no | no |
| ClojureScript | shadow-cljs, piggieback, figwheel-main | no | evaluation only |
| CLI for scripts and agents | yes: eval, docs, tests and more, with JSON output | no | no |

In short, clojure-cli.repl is the one to try if you live in deps.edn and want
the fanciest line editing, and `bb repl --connect` is a lightweight way to
poke at a running server. neorepl follows CIDER: it sends the ops CIDER
sends, so it gets the most out of a server with cider-nrepl (or babashka's
take on it) and falls back to nREPL's built-in ops and plain evaluation
without one. It doesn't add middleware of its own.

## Development

neorepl runs from the same source on the JVM (Java 17+, Clojure 1.10+) and on
[babashka](https://babashka.org) (1.13.225+). The tests run on both:

```shell
bb test             # on babashka
bb test:jvm         # on the JVM, with the default Clojure version
bb test:jvm :1.10   # ... or with an older one (:1.10, :1.11)
```

## License

Copyright © 2026 Bozhidar Batsov and contributors.

Distributed under the Eclipse Public License 1.0, the same as Clojure.
