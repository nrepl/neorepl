---
tagline: A terminal REPL for Clojure, and friends
intro: >-
  neorepl is what you get when you type `neorepl` in a Clojure project: a
  line editor that knows Clojure, the core of CIDER's tooling in a terminal,
  from the inspector to the test runner, and a command line that scripts and
  coding agents use as easily as people do. It speaks nREPL, so it works with
  the servers your editor already talks to, and starts one with cider-nrepl
  when there isn't one around.
installLabel: >-
  Install it with [bbin](https://github.com/babashka/bbin), on
  [babashka](https://babashka.org) 1.13.227 or newer
install: bbin install io.github.nrepl/neorepl
hero:
  cast: overview
  poster: end
  caption: >-
    Code gets highlighted as you type it, along with the delimiter that pairs
    with the one at the cursor. Enter indents the next line until the form is
    complete, TAB completes, the arglists of the call you're in show up under
    the input, and values come back pretty printed, with how long they took
    at the right of the prompt.

steps:
  - >-
    Get [babashka](https://babashka.org) 1.13.227 or newer and
    [bbin](https://github.com/babashka/bbin), then install neorepl with the
    command at the top of this page.
  - >-
    Run `neorepl` in a project. It connects to the server whose `.nrepl-port`
    is in the directory or one above it, and with no server around it starts
    one with cider-nrepl, the way CIDER's jack-in does, through whichever of
    Leiningen, the Clojure CLI, babashka and shadow-cljs the project uses.
  - >-
    Want an editor or a coding agent on the same server? `neorepl server`
    starts one that stays up, `,sessions` steps into another client's
    session, and [doc/editors.md](https://github.com/nrepl/neorepl/blob/main/doc/editors.md)
    and [doc/agents.md](https://github.com/nrepl/neorepl/blob/main/doc/agents.md)
    have the setups.
stepsNote: >-
  In the REPL `,help` lists the commands. Everywhere else
  `neorepl --help` and `neorepl COMMAND --help` do.
outro: Keep hacking!
---
