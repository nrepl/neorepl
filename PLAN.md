# neorepl plan (draft)

A terminal nREPL client for Clojure and friends that picks up where REPLy left off. Published as `org.nrepl/neorepl`, lives at `nrepl/neorepl` on GitHub, licensed under EPL-1.0 like nREPL and REPLy.

## What we're building

One core, three front ends:

- An interactive REPL with a proper line editor (multi-line forms, highlighting, completion, eldoc, history) and CIDER's core tooling a keystroke or a command away.
- A CLI for one-shot operations (`neorepl eval`, `neorepl doc`, `neorepl test`, ...) that works just as well for shell scripts and coding agents.
- Later, an MCP server exposing the same operations.

It only speaks nREPL, so it works with anything that runs an nREPL server: Clojure, babashka, nbb, shadow-cljs, Basilisp, ClojureCLR. Features degrade based on what the server reports in `describe`.

Non-goals:

- A non-nREPL mode like REPLy's `--standalone`. Nobody uses it and it doubles the surface.
- Reimplementing Orchard/cider-nrepl on the client.
- Becoming a full-screen IDE. The REPL stays inline; full-screen views only where inline doesn't work (inspector, test results).
- Getting bundled with Leiningen. Jack-in handles lein projects through `lein repl :headless`, which needs nothing from Leiningen.

### Why not rebel-readline, `bb repl --connect` or clojure-cli.repl?

Rebel is a line-reader library with nREPL support added later (`rebel-readline-nrepl`). It's good at editing and we should borrow its UX freely.

babashka 1.13.221 added `bb repl --connect`: a ~50-line client on `nrepl.core` (eval, completion, lookup, stdin, interrupt) on top of bb's JLine console REPL. It's a quick connect mode and a good reference for the JLine side. The same release bundled cider-nrepl's inspector (`inspect-*`), test (`test`, `test-var-query`, `retest`, ...) and `analyze-last-stacktrace` ops in `bb nrepl-server`, and the CIDER debugger is on its way. The client doesn't use them yet. Our test runner and inspector (0.2) will, so they should work against bb out of the box.

The Clojure team released [clojure-cli.repl](https://github.com/clojure/clojure-cli.repl) 0.1.0 on 2026-09-28: a JLine client in its own JVM (rewrite-clj, tools.reader, nREPL 1.7) and an nREPL server on the project classpath, started together (`clojure -M:repl`) or separately (`:serve`/`:attach`). Strong on editing: paredit ops, eval-form-at-cursor, doc-at-cursor with Java docs (`java.doc`) and clojuredocs examples, a `datafy`/`nav` inspector, user-defined prompts, hooks and server middleware from `.cljconf` config. Its doc, inspector and auto-require are its own ops (`doc`, `inspect-start`, ..., `auto-require`), so against another server it's eval only. As of 0.1.1 there's no TAB completion, eldoc or `need-input` handling, and it's Clojure 1.12+ on the JVM only. Things worth borrowing: eval-form-at-cursor, paredit widgets, the prompt showing the last eval's outcome and duration, and `^:optional` middleware.

None of them tries to be an nREPL-first tool with CIDER's tooling, jack-in, and a CLI/agent surface, which is the point of this project.

## Design principles

1. The client is a thin presenter and the smarts live on the server. Use cider-nrepl/Orchard when present, built-in nREPL ops otherwise, an eval'd snippet as a last resort. This keeps the client small and its behaviour consistent with CIDER, and Orchard improvements show up in both.
2. One small Clojure lexer (not a reader) drives everything that needs to understand code on the client: form completeness, highlighting, paren matching, symbol at point, highlighting of printed values. Reading input client-side breaks on `::alias/kw`, reader conditionals and unknown tags; nREPL's own client stopped doing it for the same reason (nrepl#182).
3. JLine is touched by exactly one namespace. If its API moves again, the damage stays there.
4. Same source on the JVM and on babashka.
5. When stdin/stdout isn't a TTY there's no line editor and no ANSI. Pipes, `M-x shell`, CI and agents all get plain text or structured data.

## Decisions

### Runtime: JVM and babashka

Both are first-class. babashka 1.13.225+ bundles everything we need: JLine 4 (4.4.7), and nREPL 1.7's `nrepl.core`, `nrepl.transport` and `nrepl.server` running from source. JLine 4 arrived in 1.13.221, but `Terminal$Signal`, which the REPL's Ctrl-C handling needs, only in 1.13.225. The same code runs on both with nothing declared in `bb.edn`.

That matters most for the CLI. Every agent call is a fresh process, and bb starts in a few dozen milliseconds instead of a second-plus for a JVM. It also means we don't need GraalVM native-image builds, at least for now.

The price: we stay inside the bb-compatible subset (no `gen-class`/AOT, `reify` only on interfaces bb supports) and use only the JLine classes bb exposes. The second constraint doubles as protection against JLine churn.

### Dependencies

Runtime deps: `org.clojure/clojure`, `nrepl/nrepl`, `org.jline/jline` 4.x. That's the whole list, and bb ships all three.

- `nrepl.core` for the client side: connect (TCP, unix sockets, URLs), transport, `client`, `client-session`, `message`. Verified from bb 1.13.225 against JVM nREPL 1.8 + cider-nrepl 0.63.1 and against `bb nrepl-server` (see "What we verified"). We only use the long-stable `nrepl.core` client API, so the version bb bundles (1.7) and the current one behave the same for us. Having nREPL on our own classpath also gives us in-process mode for free.
- JLine 4, tracking the version bb bundles. 4.x needs Java 11+ (we go with 17, see below), which is fine for a client in 2026. We start on 4, so the 3-to-4 migration pain that hit REPLy never applies, and sticking to the classes bb exposes keeps us on JLine's stable public surface. We bump JLine when bb does.
- No tools.cli, no JSON library, no rewrite-clj. Own arg parser, a tiny JSON writer/reader (only `--format json` and MCP need it), own lexer.
- cider-nrepl goes on the server's classpath at jack-in, never on ours.

### Versions

- Servers: anything that speaks nREPL. For Clojure, 1.10+ (nREPL 1.8's floor), on whatever Java the project uses.
- Client on the JVM: Java 17+, Clojure 1.10+. JLine 4 itself only needs 11, but JDK 22+ warns about JLine's native access unless the JVM gets `--enable-native-access=ALL-UNNAMED`, and that flag only exists since 17. With a 17 floor every launcher (aliases, `-T` tool, uberjar) can pass it unconditionally. Client code sticks to 1.10 APIs (no `parse-long`, `update-vals`, ...) because in-process mode runs it inside the project's JVM, which therefore also needs Java 17+.
- Client on bb: 1.13.225+.

### What we verified (bb 1.13.225, 2026-10-07)

- `nrepl.core` from bb against JVM nREPL 1.8 + cider-nrepl and against `bb nrepl-server`: eval, out/err, errors (`ex`/`root-ex`), `*1` persisting in a session, print `quota` with the `truncated` status, built-in `completions` and `lookup`, `url-connect` with `nrepl://`, and `interrupt` over the raw transport. A whole run (bb start, connect, clone, three evals) takes ~130ms wall and ~30ms CPU, with evals at 1-4ms each.
- Gotcha: a `client-session` response seq only ends when the session closes. Always send through `(nrepl/message session msg)`, which stops at `done`.
- Gotcha: never use `response-values`. It reads values on the client and blows up on quota-truncated output, and would on unknown tagged literals too. Keep `:value` strings as strings.
- Gotcha: sessions outlive connections (that's what makes CLI sessions possible), but `need-input` goes to the connection that created the session, on both nREPL 1.8 and bb's server. Reading `*in*` in a session from an earlier connection fails with "Socket closed". On the nREPL side it's `session-in` capturing the clone message's transport (session.clj:45-53); sending through `(:transport *msg*)` would fix it. Interrupts have the same problem the other way around: an `interrupt` from another connection gets the interrupted evaluation's final `interrupted`/`done` response (session.clj:293), so a client still waiting for that evaluation never sees it finish. `neorepl interrupt` is still good for freeing a session whose client is gone; the fix is for the session to remember the running message's transport and answer through it.
- Gotcha: Basilisp's, jank's and ClojureCLR's sessions only last as long as the connection, and the first two run a request for a session they don't know in a fresh one, so named sessions don't carry `*ns*`, `*1` or `*e` from one CLI call to the next there, and nothing says so. A server without `ls-sessions` can't say a cached session is gone either: one restarted on the same port answers every later call with `unknown-session`, until `neorepl sessions close` drops it.
- Gotcha: the plain REPL reads `*in*` for code with a blocking `read-line`, so Ctrl-C while code waits for a line interrupts the evaluation, but the REPL only notices with the next line, which goes to the dead evaluation as its input. The line editor doesn't have the problem.
- Gotcha: on nREPL 1.8, an eval with a `:file` (which is what `load-file` becomes) leaves `*file*` bound in the session, so everything defined there afterwards says it's from that file. `add-per-message-bindings` binds `*file*` from the message (session.clj:141), and the session saves its thread bindings minus only `*msg*` and `Compiler/LOADER` (session.clj:208-211). After `neorepl load x.clj`, `neorepl where` on a function `neorepl eval` defined in the same session points at x.clj. Dropping `*file*` there too would fix it. bb's server doesn't have the problem.
- Jack-in (2026-10-08). With the deps in `~/.m2`, the Clojure CLI and Leiningen servers are up in about 2s, bb's in under 100ms. All three say where they listen on stdout, `nREPL server started on port N on host H` (nrepl.cmdline and Leiningen) or `Started nREPL server at H:N` (bb), write `.nrepl-port` and remove it on SIGTERM. So neorepl reads the port from that line rather than racing a possibly stale port file.
- Gotcha: a server neorepl starts is in the terminal's foreground process group, so Ctrl-C meant for an evaluation would kill it too. Java can't start a process in a group of its own, but a signal ignored before `exec` stays ignored, and JVMs leave alone what they find ignored, the way `nohup` works. So the server runs under `sh -c 'trap "" INT; exec "$@"'`; Leiningen's project JVM inherits that too. Windows needs another answer.
- shadow-cljs (3.5.5, 2026-10-08). `npx shadow-cljs -d cider/cider-nrepl:VERSION server` prints `shadow-cljs - nREPL server started on port N`, which the nREPL pattern above matches, and writes only `.shadow-cljs/nrepl.port`. Its nREPL loads cider-nrepl's middleware by itself when it's on the classpath. The port comes from `:nrepl {:port N}` in shadow-cljs.edn alone; `--config-merge` only reaches build configs. CIDER also injects nREPL itself, but shadow-cljs bundles nREPL 1.3.1, and on 1.6+ the middleware linearizer warns a dozen times at startup that `cider.piggieback/wrap-cljs-repl`, which cider-nrepl's and shadow-cljs's descriptors require, isn't in the stack. So neorepl leaves shadow-cljs its own nREPL.
- Gotcha: Node resets every signal disposition at startup, and child processes it spawns get the defaults too, so the `trap '' INT` trick doesn't reach the JVM that `npx shadow-cljs` starts. neorepl runs npx through a few lines of Node instead, which ignore SIGINT and spawn npx `detached`, in a session of its own, where the terminal's signals don't reach. They pass SIGHUP, SIGTERM and SIGQUIT on to npx's process group, so the server doesn't outlive them when the terminal goes away.
- ClojureScript sessions (shadow-cljs 3.5.5, piggieback 0.6.1 with a Node REPL env). Both keep the ClojureScript state in the nREPL session: cider-nrepl's `complete`, `info` and `macroexpand` only answer in ClojureScript when the request carries that session, and give Clojure's answers in any other. `describe` with a `:session` reports its namespace as `:aux :current-ns`, ClojureScript's included, on nREPL 1.3+ and babashka's server, which is how the REPL's prompt starts where the session is (shadow-cljs sessions start in `shadow.user`). Values get pretty printed by the print middleware in both. Errors: piggieback sends `eval-error` with the message as `:err`; shadow-cljs only `:err`, with a JavaScript stack trace, and `analyze-last-stacktrace` finds nothing either way.
- Gotcha: shadow-cljs evaluates ClojureScript off the session's thread, so `interrupt` answers `session-idle` while the JavaScript runtime is busy, and the evaluation goes on until shadow-cljs gives up on the runtime, which takes a while. Later evaluations in the session wait behind it. The REPL says so and lets a second Ctrl-C stop waiting, and `neorepl eval --timeout` doesn't claim it interrupted the evaluation.
- piggieback (0.8.0, 2026-10-09). Since 2019 it loads without ClojureScript, as middleware that does nothing and a `cljs-repl` that says ClojureScript is missing, so jack-in adds it to every deps.edn and Leiningen server. With ClojureScript on the classpath it loads `cljs.repl` and its own implementation as nREPL builds the middleware stack, which takes a Clojure CLI server from 1.25s to 1.67s to start; without ClojureScript it costs nothing. Loading them on the first `cljs-repl` instead, with the session vars that need the analyzer set up there, takes the cost away, and piggieback's tests pass that way. Leiningen takes it through `update-in :repl-options:nrepl-middleware conj`, next to the middleware the cider-nrepl plugin adds there. `describe` reports `:aux :piggieback {:cljs-repl "inactive"}`, or `"unavailable"` without ClojureScript, which a `,cljs` command could use. figwheel-main 0.2.20's `(figwheel.main.api/start "dev")` builds and switches the session to a Node runtime, and completion, eldoc, `,doc` and `:cljs/quit` work there as with shadow-cljs.
- Gotcha: `-Sdeps` replaces a piggieback in the project's top-level `:deps`, a `:local/root` fork included, as it does nREPL and cider-nrepl; one in an alias's `:extra-deps` still wins, and so does a Leiningen project's own.
- Gotcha: piggieback only evaluates the first form of an `eval` message, so an input of several forms loses the rest in a ClojureScript session, silently. Its `do-eval` reads a single form, where nREPL's eval reads them in a loop, answering each one, carrying on after an evaluation error and stopping at a read error.
- Gotcha: Ctrl-C in a piggieback session with a Node runtime interrupts the thread waiting for Node, and the REPL reports the evaluation interrupted, but the JavaScript keeps running. Later evaluations, `:cljs/quit` and closing the session all wait behind it, forever for a busy loop. Code that ends after all hands its result to the next evaluation, and every result after that is one behind. Both come from `cljs.repl.node`: every nREPL thread shares one queue of results, and tearing down waits for Node to exit, which it doesn't while busy. Node can stop a busy evaluation and keep its state, through `vm.runInThisContext`'s `breakOnSigint`, so the fix belongs in ClojureScript: evaluate that way, send Node SIGINT when the thread waiting for it gets interrupted, and match results to evaluations.
- Gotcha: a busy Node outlives a server killed with SIGTERM, spinning a core. jack-in's server stops with all its descendants, Node included.
- Gotcha: Leiningen runs the project in a JVM of its own, and killing the `lein` process leaves both JVMs running. Stopping a server means SIGTERM to the whole process tree (`ProcessHandle.descendants`, which bb supports, though not as a type hint).
- Testing gotcha: pyte, which `spike/drive.py` renders with, forgets the cursor `ESC 7` saved after the first `ESC 8`. JLine's grapheme width probing restores twice, so a pyte screen has the REPL draw from the top left over whatever came before. Real terminals don't.
- Tests (2026-10-11, cider-nrepl 0.63.1 and bb 1.13.225). Both servers' `test-var-query` and `retest` answer in the same shape: one response with the `:summary`, the `:results` by namespace, test and assertion and the `:elapsed-time`, then `done`. cider-nrepl's `:file` is only the file's name and bb has none, so neorepl looks the test up with `info` for its path. With cider-nrepl, `=` assertions in namespaces compiled after its test middleware loaded report the values as `:expected` and `:actual`, the others the forms, as clojure.test does. Without the test ops, neorepl evaluates clojure.test in a copy of the session with a `report` of its own, which gives the same report. `test-stacktrace` takes the `:ns`, `:var` and `:index` of an error from the results and answers like `analyze-last-stacktrace`; cider-nrepl's frames run on into orchard's test runner, so neorepl prints them only down to the runner's. With `:stream "true"`, cider-nrepl 0.63.1 sends `:test-event`s ahead of the report: `begin-ns` with the `:ns`, `end-var` with the `:var`, the running `:summary` and the `:results` that didn't pass, and `end-ns` with the `:elapsed-time`. neorepl prints the `begin-ns` ones the way clojure.test does, as `Testing my.ns`. Older versions and bb 1.13.226 ignore the key, and the evaluated clojure.test prints none.
- Gotcha: babashka's `test-var-query`, as of 1.13.226, never answers when a test or a fixture throws outside an assertion: its result carries `:fault true`, a boolean, which bencode can't write, so the reply never goes out. 1.13.225 logs the bencode error and 1.13.226 says nothing. `neorepl test` waits for it, until `--timeout` if there is one. Filed as [babashka#2201](https://github.com/babashka/babashka/issues/2201), with the fix: `wire` in `babashka/nrepl/impl/cider.clj` turning booleans into strings, as cider-nrepl sends `:fault "true"`.
- Gotcha: both servers go quiet on a `test-var-query` for a test in a namespace that isn't loaded, with no response at all, and on bb the session stays busy for good. cider-nrepl also runs every test it finds for a test that isn't there, since the query's `:exactly` comes out empty. So neorepl requires the namespaces and checks the tests exist, in an evaluation of its own in a copy of the session, before it asks. The copy keeps the session's language, so the same code checks ClojureScript tests, with `require` taking the names as quoted symbols, the only way ClojureScript's takes them. The `require` and the check are evaluations of their own: piggieback 0.6.2 evaluates only the first form of an eval's code.
- Tests in ClojureScript (2026-10-16, cider-nrepl 0.63.1). In a session in ClojureScript, cider-nrepl runs `test-var-query` and `retest` through `cljs.test` in the runtime, with evaluations of its own, and answers in the same shape as for Clojure, with no `:file` and no streamed events. An error's `:error` is a map, its `:message` and the JS `:stack`, and `test-stacktrace` answers with `js/Error` and the stack in the `:message`, with no frames, so neorepl shows the message only. A namespace that isn't loaded gets `namespace-not-found` and a test that isn't there runs nothing. Those evaluations go into the session, so a run leaves the report as its `*1`. Through piggieback 0.6.2 and node it works; through shadow-cljs 3.2.1 it doesn't, see the gotcha.
- Gotcha: cider-nrepl's ClojureScript tests hand their evaluations to the middleware below its test middleware, which it expects to be piggieback's. shadow-cljs's middleware isn't there, so on shadow-cljs the evaluations go to Clojure, in the session's `cljs.user`, and the run fails with "Unable to resolve symbol: require". Both expect piggieback, which puts them above it, but nothing orders them against each other, and on shadow-cljs 3.2.1 the stack comes out with `wrap-cljs-repl` at 25, `wrap-test` at 26 and shadow-cljs's middleware at 30, outside it. Filed as [cider-nrepl#1054](https://github.com/clojure-emacs/cider-nrepl/issues/1054): `wrap-test` could expect shadow-cljs's middleware too, or send its evaluations from the top of the stack.
- Gotcha: cider-nrepl's evaluations for ClojureScript tests go into the user's session, so a run leaves the report the runtime collected as the session's `*1`.
- Gotcha: bb's `test-var-query` ignores `:project?` and `:load-project-ns?`, so `neorepl test` without arguments runs the tests of every namespace that's loaded, whether it's the project's or not.
- Gotcha: what cider-nrepl's tests print doesn't come with the test op's responses. It stays in the session's output buffer past `done` and goes out when something flushes it, like the next test run, under an earlier message's id and over that message's connection, which for `neorepl test` is a connection that's gone. So the CLI never shows it and the REPL shows it late, if at all. The streamed test events flush it as the tests run, which brings it to the REPL in time, but not to `neorepl test`. bb sends it with the test op's responses.
- Inspector (2026-10-15, cider-nrepl 0.63.1 and bb 1.13.225). Both start it with an `eval` carrying `inspect "true"`, whose `:value` is the view rather than the printed value, and answer `inspect-push` (`:idx`), `inspect-pop`, `inspect-next-page` and the like with the new view, the same way: a printed list of strings, `(:newline)` and `(:value text index)`. The inspector is per session, so `neorepl inspect` can go on where the last call left off. The eval still sets `*1`. bb has no `inspect-last-exception`.
- Gotcha: when the code fails, cider-nrepl's inspecting eval only says `inspect-eval-error`, never `eval-error`, so neorepl reads that as the evaluation failing. bb says both.
- Stack traces (2026-10-15, cider-nrepl 0.63.1 and bb 1.13.225). `analyze-last-stacktrace` answers with a response per cause, outermost first. cider-nrepl's frames carry the `:var` and a `:file-url`, its `:name` is the class and method (`user$outer/invokeStatic`); bb's `:name` is the var itself, with `:ns` and `:fn`, and the frames of causes after the first are its interpreter's (`sci.impl...`), flagged tooling. Only cider-nrepl's `:file-url` has a frame's full path, so neorepl opens a frame through `info` on its var, as `,edit` does, which works on both.
- JLine classes bb exposes: `TerminalBuilder`, `LineReaderBuilder`, `LineReader`, `LineReaderImpl`, `Parser`, `ParsedLine`, `Completer`, `Candidate`, `Highlighter`, `Widget`, `Reference`, `KeyMap`, `BindingReader`, `Display`, `AttributedString(Builder)`, `AttributedStyle`, `Size`, `Attributes` (and `Attributes$LocalFlag`), `InfoCmp$Capability`, `EOFError`, `UserInterruptException`, `EndOfFileException`. Missing: `org.jline.utils.Status` (handy for an eldoc status line), `DefaultParser`, `DefaultHistory`, `DumbTerminal`. Only `Status` is a real loss.

### How it runs

- Connect. `neorepl` looks for `.nrepl-port` (and `.shadow-cljs/nrepl.port`, etc.) in the current directory and its parents, checks the server is alive and connects. Also `--host`/`--port` and the URLs nREPL 1.8's cmdline understands (`nrepl://`, `nrepls://`, `nrepl+unix:`). `nrepl.cmdline` isn't available in bb, so we map URLs to `nrepl.core/connect` options ourselves; it's a few lines. `nrepl.tls` does load in bb.
- Jack-in. No server found: detect the project (`project.clj`, `deps.edn`, `bb.edn`, `shadow-cljs.edn`, nearest first, in that order within a directory, except that shadow-cljs comes first when its `:deps` or `:lein` say it builds with the project's deps.edn or project.clj), start a server with nREPL, cider-nrepl and piggieback injected (shadow-cljs only gets cider-nrepl), connect, and shut it down when the REPL ends. Zero to REPL in one command, like `lein repl` today. CIDER's Windows quoting lessons still need porting.
- In-process. `neorepl --in-process` starts an nREPL server in neorepl's own process, with cider-nrepl when it's on the classpath, and connects over loopback. With neorepl as a dependency, `clojure -M:neorepl` gets you a single JVM.
- Headless. `neorepl server` is jack-in without the REPL; the server writes the port file and stays up until neorepl gets Ctrl-C or SIGTERM. For agents and editor users.

## The feature ladder

Each feature tries the rungs left to right.

| Feature | cider-nrepl | Built-in nREPL | Eval fallback |
|---|---|---|---|
| Completion | `complete` | `completions` | none |
| Doc / eldoc | `info`, `eldoc` | `lookup` | `clojure.repl/doc` |
| Source | `info` (file/line) | `lookup` | `clojure.repl/source-fn` |
| Find definition | `info` | `lookup` | var metadata |
| Apropos | `apropos` | | `clojure.repl/apropos` |
| Errors | `analyze-last-stacktrace` | | `Throwable->map` on `*e` |
| Macroexpansion | `macroexpand` | | `macroexpand-1`, `clojure.walk/macroexpand-all` |
| Tests | `test-var-query`, `retest` | | `clojure.test` with a capturing report fn |
| Reload | `refresh`, clj-reload | | `require :reload` |
| Inspector | `inspect-*` | | not available |
| Pretty printing | `cider.nrepl.pprint` printers | `print` middleware | |
| Interrupt, stdin, load-file, sessions | | built-in ops | |

Tooling requests that don't evaluate anything (completion, eldoc, ...) go to the user's session, as CIDER sends them, since that's where a ClojureScript REPL keeps its state. The eval fallbacks run outside it, so they don't clobber `*1`/`*e`. The error fallback has to read `*e` from the user session, so it evaluates in a copy.

## Interactive REPL

- Line editor: multi-line input where Enter submits only complete forms (the lexer decides), auto-indented continuation lines, highlighting, paren matching, a completion menu with type/ns annotations, eldoc in a status line, persistent per-project history with search, Emacs and vi keymaps.
- Output: `out`/`err` streamed in distinct styles. Output from background threads goes above the prompt (`LineReader#printAbove`) instead of over it. Values are pretty-printed on the server and highlighted on the client. We never read values client-side, so records, tagged literals and cljs objects just work.
- Ctrl-C sends `interrupt` while something's running and clears the line otherwise. Ctrl-D on an empty line exits. `need-input` prompts for stdin (REPLy never got this fully right).
- Errors: a one-line summary by default (class, message, location) and the full analyzed trace on demand.
- Commands: CIDER-style comma commands (`,doc`, `,source`, `,apropos`, `,test`, `,reload`, `,inspect`, `,ns`, `,sessions`, `,quit`). A leading comma is whitespace to the Clojure reader, so it can't collide with real code. `:repl/quit` works too.
- Keys: rebel-readline's C-x prefix conventions where they exist (C-x C-d doc, C-x C-s source, C-x C-a apropos), which bb's console REPL also follows.
- Full-screen views for the inspector, test results and stacktrace navigation, built on JLine's `Display` and the alternate screen. No TUI framework.
- ClojureScript: shadow-cljs and piggieback sessions, switching between clj and cljs. CIDER's history says this is where the dragons live, so it gets its own milestone.

## CLI and agents

Rules for every subcommand: plain output when not on a TTY, `--format text|edn|json`, meaningful exit codes (1 for an eval error, 124 for a timeout, like `timeout(1)`), and parity with the comma commands.

```
neorepl [repl]                 connect or jack-in, interactive
neorepl eval CODE | -f FILE | -
neorepl load FILE
neorepl doc | source | info SYM
neorepl apropos REGEX
neorepl complete PREFIX [--ns NS]
neorepl macroexpand FORM
neorepl test [NS | VAR ...]
neorepl server                 headless jack-in, writes the port file
neorepl ports                  servers for this directory
neorepl sessions [close NAME]
neorepl interrupt
neorepl check FILE             delimiter check via the lexer
neorepl mcp                    later
```

What agents need beyond that:

- Named persistent sessions (`--session NAME`, defaulting to one per project and server), so `*ns*`, `*e` and `*1` survive between calls. Session ids are cached locally, validated with `ls-sessions` and re-cloned if the server restarted.
- `--timeout` that sends `interrupt` before giving up, so an infinite loop can't wedge the server.
- Output caps: the print middleware's `quota` plus a cap on `out`/`err`, so a stray `(range)` doesn't eat an agent's context.
- Structured results: `{:values [...] :out "..." :err "..." :ns "user" :error {...}}`.
- `neorepl ports` for projects running several servers (clj + shadow + bb).
- `neorepl check` because unbalanced delimiters are the most common way agents break Clojure files, and the lexer gives us the check for free.
- A shipped agent skill (SKILL.md) and an AGENTS.md snippet. `--help` stays short and example-driven, since agents read it.
- MCP after that, as a thin JSON-RPC layer over the same feature functions. CLI first: agents drive CLIs fine, and a CLI costs no context until it's used.

Prior art to beat: Bruce Hauman's clojure-mcp-light (`clj-nrepl-eval`, session per server, port discovery) and clojure-mcp.

## Code layout

```
deps.edn            clojure, nrepl, jline; aliases for tests and in-process mode
bb.edn              same paths, no deps (bb bundles them), bb tasks
src/neorepl/
  main.clj          entry point, subcommands
  cli.clj           arg parsing, help text layout
  nrepl.clj         on top of nrepl.core: URLs, sessions, routing responses by id, async output
  evaluation.clj    following an evaluation: output, stdin, interrupts, output caps, the result
  sessions.clj      named sessions that outlive CLI calls
  discovery.clj     port files, liveness checks
  features.clj      the feature ladder; the API every front end uses
  lookups.clj       the lookups the CLI's commands and the REPL's share
  jack_in.clj       project detection, server command building, process lifecycle
  shutdown.clj      cleaning up when neorepl gets Ctrl-C or SIGTERM
  lexer.clj         completeness, highlighting, symbol at point, delimiter checks
  editor.cljc       JLine glue; the only namespace that imports org.jline
  repl.clj          interactive loop and comma commands
  render.clj        colors, themes, errors, test reports
  json.clj          minimal JSON
  xdg.clj           where the cache and history files go
```

Size goal: the core stays around 3-4k lines through 1.0. If it grows well past that, something probably belongs on the server.

## Testing

- Unit: a lexer corpus (completeness and highlighting), arg parsing, rendering, JSON.
- Integration against real servers: plain nREPL, nREPL with cider-nrepl, `bb nrepl-server`, then shadow-cljs, nbb and Basilisp as they come online. Every feature gets tested on every rung of its ladder.
- Editor: a JLine terminal over in-memory streams, scripting keystrokes and asserting on the output.
- CI (GitHub Actions): JVM (Clojure 1.10/1.11/1.12, JDK 17 through 25) and bb (oldest supported and latest), on Linux, macOS and Windows.

## Milestones

Each one is a release people can use. The first three stages below all went into 0.1 (2026-10-10).

0.1, foundations
- Client layer over `nrepl.core`, sessions, server discovery, URLs.
- `eval`, `load`, `ports`, `sessions`, `interrupt`, `check` with formats, timeouts and output caps.
- Basic interactive REPL: multi-line input, history, highlighting, Ctrl-C, stdin, ns in the prompt, pretty printing.
- Runs on the JVM and bb.

0.1, CIDER essentials
- Completion, eldoc, doc/source/apropos/info, macroexpansion, error rendering with stacktrace analysis, load-file and reload, all through the ladder.
- Comma commands, find definition (prints the location; `,edit` opens `$EDITOR`).

0.1, jack-in
- deps.edn, lein, bb and shadow-cljs projects, cider-nrepl injection, `neorepl server`, in-process mode.
- clj/cljs sessions, through shadow-cljs or the piggieback jack-in adds to deps.edn and Leiningen projects.

0.2, tests and inspection
- Test runner with summaries, rerun failures, jump to a failure. Done: `neorepl test` and `retest`, `,test` and `,retest`, with each failure's file and line. Done too: the stack trace of each error in the report, from `test-stacktrace`, or clojure.test's exception without it, and a `Testing` line for each namespace as the tests run, from cider-nrepl's `stream`. Done too: ClojureScript tests, in a session in ClojureScript, through cider-nrepl and piggieback. Still to do: the frames of ClojureScript errors, from the JS stack, and ClojureScript tests on shadow-cljs, which takes a fix in cider-nrepl ([cider-nrepl#1054](https://github.com/clojure-emacs/cider-nrepl/issues/1054)).
- Full-screen inspector and stacktrace browser. Done: `,inspect` in the REPL, full-screen, and `neorepl inspect`, both on cider-nrepl's `inspect-*` ops, which babashka's server has too. Done too: `,stacktrace`, the last error full-screen, to open a frame's source in the editor, and the errors of the last test run the same way, until an evaluation fails. Still to do: `inspect-def-current-value` (needs a prompt in the full-screen view), a page size that fits the screen, inspecting an error's ex-data from the stack trace (cider-nrepl's `inspect-last-exception`, which babashka's server lacks).

0.3, agents, round two
- MCP server, the skill, docs for common agent setups.

1.0
- Docs site, installers (bbin, Clojure tool, Homebrew, uberjar; native-image only if bb turns out not to be enough), Windows polish, a CLI contract we promise to keep stable.

## JLine spike (2026-10-07, macOS)

`spike/jline_repl.clj` is a ~400-line REPL client: JLine 4 + `nrepl.core`, the lexer sketch, one reader thread routing responses by id. The same file runs unchanged on bb and the JVM. `spike/drive.py` drives it through a real pty and renders the screen with pyte, so the results below come from an emulated terminal, not from eyeballing.

Configurations, all passing the full scenario:

| Client | Server | JLine terminal |
|---|---|---|
| bb 1.13.225 | JVM nREPL 1.8 + cider-nrepl | FFM |
| JVM 21, Clojure 1.12 | same | JNI |
| JVM 21, Clojure 1.10.3 | `bb nrepl-server` (nREPL 1.7) | JNI |
| JVM 25, Clojure 1.12 | JVM nREPL 1.8 + cider-nrepl | FFM |

What works on all of them:

- Enter on an incomplete form continues the input; the lexer decides, the `Parser` throws `EOFError`. Strings, char literals (`\(`), `#_`, regexes and comments all behave.
- Highlighting through a `Highlighter` reify.
- TAB completion through a `Completer` backed by `complete`/`completions`, with candidate types in the menu.
- Eldoc via `LineReaderImpl#setTailTip` (public API) from wrapped `self-insert`/`backward-delete-char` widgets. Limitation: JLine only draws the tail tip while the cursor is at the end of the buffer. For mid-buffer eldoc we need `Status` (not in bb) or the private `post` field bb's own REPL uses; setting `post` via reflection works from bb scripts too, but it's a hack.
- C-x C-d widget printing doc above the prompt with the buffer intact.
- Output arriving while the user is typing (a `future` printing after its eval finished) lands above the prompt via `printAbove` from the reader thread, and the half-typed input survives.
- Ctrl-C during an eval sends `interrupt` through a `Terminal$SignalHandler`; Ctrl-C at the prompt clears the line; Ctrl-D exits 0.
- `need-input` reads a raw line (no completeness check) and sends `stdin`.
- Wide characters (`日本語`) render and the cursor lands where it should.
- Persistent history (C-p, Up).
- With piped stdin JLine falls back to a dumb terminal. Works, but echoes prompts, so the non-TTY path should skip JLine entirely, as planned.

Time to an interactive prompt (connect, `describe`, two clones, JLine setup): ~210ms on bb, ~850ms on JVM 21 running from source.

Findings that change things:

- JDK 25 without `--enable-native-access=ALL-UNNAMED` prints the restricted-method warning, and JLine's first redraw half-overwrites it. With the flag it's clean. Hence the Java 17 floor above. The uberjar can also carry `Enable-Native-Access: ALL-UNNAMED` in its manifest.
- On the JVM, JLine logs a warning at startup because our `ParsedLine` isn't a `CompletingParsedLine`. bb doesn't expose `CompletingParsedLine`, so we can't reify it there, and bb has no `java.util.logging` to quiet the warning with. editor.cljc reifies `CompletingParsedLine` on the JVM and plain `ParsedLine` on bb, behind a `:bb` reader conditional.
- On an interrupted `Thread/sleep` the server sometimes sends an `InterruptedException` error and sometimes just `interrupted`, depending on timing. The client has to render both sensibly.
- `%P` in `SECONDARY_PROMPT_PATTERN` pads with the character that follows it (`%P#` gives `###`), so it's `%P #_=> `.
- On bb, Ctrl-C during a `readLine` arrives twice: as a signal that interrupts the reading thread, and as a `^C` character for JLine's `interrupt` widget. The second one interrupted the next read too, so the editor drops a `^C` still waiting after an interrupt. Worth raising with borkdude along with the classes below.

Not covered yet: Linux (Docker wasn't running) and Windows. Neither should surprise us given bb ships JLine on both, but Windows needs a real check before 0.1. Concurrent tooling requests during an eval also stay untested, since the interactive REPL can't issue them yet.

## Open questions

1. Which runtimes get CI from day one? Proposal: Clojure, bb and shadow-cljs, since 0.1 supports all three, and the rest as people show up.
2. Things to raise with borkdude: exposing `org.jline.utils.Status` (mid-buffer eldoc without reflection) and `org.jline.reader.CompletingParsedLine` (for reify) in bb, how `bb repl --connect` and neorepl should relate long term, and `bb nrepl-server`'s `info` op: it has no `:line` or `:column`, which its `lookup` has, and it resolves files like `clojure/core.clj` against the working directory, so `neorepl where` on bb points at the top of the file or at a file that isn't there. Also, `bb nrepl-server` answers an interrupted evaluation with `done` straight away and sends the evaluation's InterruptedException after it, as output for a message that's over. And `test-var-query` going quiet, and taking the session with it, for a test whose namespace isn't loaded, ignoring `:project?` and `:stream`, and lacking `inspect-last-exception`, see the test gotchas. Filed: `test-var-query` going quiet for a test that throws outside an assertion, [babashka#2201](https://github.com/babashka/babashka/issues/2201).
3. Things to raise in nREPL: `need-input` going to the connection that created the session, and an interrupted evaluation's final response going to the interrupter's connection, see the gotcha above; the warnings 1.6 added when a middleware's descriptor requires one that isn't in the stack fire a dozen times on every shadow-cljs start with cider-nrepl, for `cider.piggieback/wrap-cljs-repl`, which neither needs there.
4. Things to raise in cider-nrepl: `test-var-query` never answering a query for a test in a namespace that isn't loaded, running every test for one that isn't there, what tests print going out late, with another message, and ClojureScript tests leaving `*1` changed, see the test gotchas. Filed: ClojureScript tests not running on shadow-cljs, [cider-nrepl#1054](https://github.com/clojure-emacs/cider-nrepl/issues/1054).
5. Things to raise in piggieback: evaluating every form of an `eval` message, the way nREPL's own eval does, and loading ClojureScript on the first `cljs-repl` rather than at startup. In ClojureScript: interrupting the Node REPL, see the gotcha above.
6. `neorepl` shares its name with neorepl.nvim (a Neovim Lua REPL, ~80 stars). Different ecosystems, probably fine.
