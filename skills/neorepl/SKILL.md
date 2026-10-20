---
name: neorepl
description: Work with a running Clojure, ClojureScript or babashka program through its nREPL server, with the neorepl command line client. Use it to evaluate code, load edited files, run tests, look up docs and source, read stack traces, trace function calls and repair unbalanced parens.
---

# neorepl

`neorepl` talks to the nREPL server of the project you're in. It finds it
through the `.nrepl-port` file in the current directory or a parent, so run
it from inside the project. Every command takes `--help`, and `--format json`
when you'd rather parse the output than read it.

## Getting a server

`neorepl ports` lists the servers it can see. When there's none, ask the
user before starting one, since they may want their editor's. If they say
go ahead, start one in the background. It keeps running until it's stopped:

```shell
neorepl server
```

It prints its `nrepl://` URL once it's up, and writes the port file the
other commands find.

## Sessions

Evaluations keep their state, `*ns*`, `*1` and `*e`, between calls in a
named session. Use one of your own, `--session agent` (`-s agent`), on the
commands that evaluate, so you don't trample the user's: `eval`, `load`,
`test`, `retest`, `pst` and `interrupt`. neorepl's MCP tools use that one
too. `neorepl sessions close agent` starts it over.

## After editing a file

Check the delimiters balance, then load the file, then run its tests:

```shell
neorepl check src/my/app.clj
neorepl load -s agent src/my/app.clj
neorepl test -s agent my.app-test
```

When `check` reports a problem, `neorepl check --fix src/my/app.clj` repairs
it from the indentation, the way parinfer would, and writes the file back.
Read the result: it trusts the indentation, so a line indented wrong ends up
in the wrong form. Fix it by hand when `--fix` says it can't.

Loading matters: `neorepl test` loads namespaces that aren't loaded yet, but
not your changes to ones that are. `neorepl reload` reloads every namespace
that changed since the last reload, when the server has cider-nrepl.

## Evaluating

```shell
neorepl eval -s agent '(my.app/parse-date "2026-10-17")'
neorepl eval -s agent '(range 5)'
neorepl eval -s agent '(map inc *1)'
```

What the code prints shows up as it runs, and each value on a line of its
own. Pass code with single quotes in it on standard input, instead of
fighting the shell:

```shell
neorepl eval -s agent - <<'EOF'
(in-ns 'my.app)
(keys (ns-publics 'my.app))
EOF
```

`--timeout 30` interrupts an evaluation that runs longer than 30 seconds and
`--max-output 2000` cuts long values and output, both worth passing when
you're not sure what the code does. `neorepl interrupt -s agent` stops an
evaluation left running by a call that got killed.

## When something fails

`eval` and `load` print the error and exit 1. `neorepl pst -s agent` prints
the stack trace of the session's last error. `neorepl test` prints each
failure with what it expected, what it got and where, and `neorepl retest
-s agent` reruns only the tests that didn't pass.

To see how a function gets called, and what it returns, trace it, run the
code that calls it, and stop tracing it after. It takes cider-nrepl on the
server:

```shell
neorepl trace my.app/parse-date
neorepl eval -s agent '(my.app/import "data.csv")'
neorepl untrace my.app/parse-date
```

## Looking things up

```shell
neorepl doc clojure.core/update-in
neorepl source --ns my.app start
neorepl where my.app/start
neorepl apropos update
neorepl macroexpand --all '(-> x (f) (g))'
```

They resolve names in `user`, or the namespace `--ns` names, apropos aside.
With `-s agent` they look as your session sees things, in its namespace, and
in ClojureScript when it's in ClojureScript, where you name the namespace
with `--ns`.

## Exit status

0 is success, 1 means the code, test or check failed, 2 is a mistake in the
command line, 3 means there's no server to talk to and 124 a timeout.

## What not to do

Don't run `neorepl` without a command: that's the interactive REPL, which
needs a person at a terminal. Don't edit a file to see what some code does
when `eval` can tell you.
