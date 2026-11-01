# Editing in an editor, evaluating in neorepl

The REPL runs in a terminal beside the editor, and the editor sends code to
the same server. Vars are global, so the REPL sees whatever the editor
evaluates, and the other way around. All it takes from the editor is running
`neorepl` from the file's directory, where it finds the project's server:

- `neorepl load FILE` when the file's saved, to load all of it.
- `neorepl eval -f FILE --line N:COL -` for the top-level form at the cursor,
  with the buffer's text on standard input, so it works on a buffer that
  isn't saved. It evaluates the form in the file's namespace, after the
  file's `ns` form, so a namespace nothing's loaded yet works too, and what
  the form defines knows its file and line.
- `neorepl eval --ns NS -` for a selection, in the buffer's namespace.

Start the server with `neorepl server` in the project, which keeps it up
until you stop it, or with the REPL, `neorepl`, which stops it when the REPL
ends. The setups below evaluate in the session called `editor`, which keeps
the editor's `*1` and `*e` apart from the REPL's. `,sessions editor` in the
REPL steps into it, to see the editor's last error with `,pst`, and
`,sessions -` steps back out.

They're checked with Neovim 0.12, Emacs 31 and Helix 25.07.

## Neovim

In `init.lua`, or a file it loads. With `maplocalleader` set to `,`, `,ee`
evaluates the form at the cursor and `,e` the selection:

```lua
-- Evaluating in neorepl from Neovim 0.10+, without a plugin.
local function neorepl(args, input)
  -- In the file's directory, so it finds the project's server.
  vim.system(vim.list_extend({ 'neorepl' }, args),
    { stdin = input, text = true, cwd = vim.fs.dirname(vim.api.nvim_buf_get_name(0)) },
    function(r)
      vim.schedule(function()
        vim.notify(vim.trim(r.stdout .. r.stderr),
          r.code == 0 and vim.log.levels.INFO or vim.log.levels.ERROR)
      end)
    end)
end

local function buffer_ns()
  for _, line in ipairs(vim.api.nvim_buf_get_lines(0, 0, 50, false)) do
    local ns = line:match('^%(ns%s+([%w%.%-_*+!?<>=/]+)')
    if ns then return ns end
  end
end

vim.api.nvim_create_autocmd('FileType', {
  pattern = 'clojure',
  callback = function(ev)
    local file = vim.api.nvim_buf_get_name(ev.buf)
    local map = function(mode, keys, f) vim.keymap.set(mode, keys, f, { buffer = ev.buf }) end
    -- The top-level form at the cursor, as the buffer has it, saved or not.
    map('n', '<localleader>ee', function()
      local row, col = unpack(vim.api.nvim_win_get_cursor(0))
      local text = table.concat(vim.api.nvim_buf_get_lines(0, 0, -1, false), '\n')
      neorepl({ 'eval', '-s', 'editor', '-f', file, '--line', row .. ':' .. (col + 1), '-' }, text)
    end)
    -- The selection, in the buffer's namespace.
    map('x', '<localleader>e', function()
      local text = table.concat(vim.fn.getregion(vim.fn.getpos('v'), vim.fn.getpos('.'),
        { type = vim.fn.mode() }), '\n')
      vim.api.nvim_feedkeys(vim.keycode('<Esc>'), 'n', false)
      local ns = buffer_ns()
      neorepl(vim.list_extend({ 'eval', '-s', 'editor' }, ns and { '--ns', ns, '-' } or { '-' }), text)
    end)
    -- The whole file, every time it's saved.
    vim.api.nvim_create_autocmd('BufWritePost', {
      buffer = ev.buf,
      callback = function() neorepl({ 'load', file }) end,
    })
  end,
})
```

## Emacs

With clojure-mode, C-M-x evaluates the form at point and C-c C-r the
region, and what they return shows in the echo area:

```elisp
;;; Evaluating in neorepl from clojure-mode, without CIDER  -*- lexical-binding: t -*-

(defun neorepl--run (args &optional input)
  "Run neorepl with ARGS, sending it INPUT, and show what it prints.
It runs in the buffer's directory, so it finds the project's server."
  (let ((process (make-process
                  :name "neorepl"
                  :buffer (generate-new-buffer " *neorepl*")
                  :command (cons "neorepl" args)
                  :connection-type 'pipe
                  :sentinel (lambda (process _event)
                              (unless (process-live-p process)
                                (with-current-buffer (process-buffer process)
                                  (message "%s" (string-trim (buffer-string))))
                                (kill-buffer (process-buffer process)))))))
    (when input
      (process-send-string process input))
    (process-send-eof process)))

(defun neorepl-eval-defun ()
  "Evaluate the top-level form at point, as the buffer has it, saved or not."
  (interactive)
  (neorepl--run (list "eval" "-s" "editor" "-f" buffer-file-name
                      "--line" (format "%d:%d" (line-number-at-pos)
                                       (1+ (- (point) (line-beginning-position))))
                      "-")
                (buffer-substring-no-properties (point-min) (point-max))))

(defun neorepl-eval-region (beg end)
  "Evaluate the region, in the buffer's namespace."
  (interactive "r")
  (let ((ns (clojure-find-ns)))
    (neorepl--run (append '("eval" "-s" "editor") (when ns (list "--ns" ns)) '("-"))
                  (buffer-substring-no-properties beg end))))

(defun neorepl-load-file ()
  "Load the buffer's file."
  (interactive)
  (neorepl--run (list "load" buffer-file-name)))

(add-hook 'clojure-mode-hook
          (lambda ()
            (local-set-key (kbd "C-M-x") #'neorepl-eval-defun)
            (local-set-key (kbd "C-c C-r") #'neorepl-eval-region)
            (add-hook 'after-save-hook #'neorepl-load-file nil t)))
```

## Helix

In `~/.config/helix/config.toml`. A key can't send Helix's buffer to a
command's standard input, so `+e` saves the file before evaluating the form
at the cursor. `+r` puts what the selections return after them, as comments.
Commands run in Helix's working directory, so start it in the project:

```toml
[keys.normal."+"]
# The top-level form at the cursor, saved first, since neorepl reads the file.
e = [":write", ":sh neorepl eval -s editor -f %{buffer_name} --line %{cursor_line}:%{cursor_column}"]
# The selections, in the file's namespace, with what they print and return
# after them as comments.
r = ''':pipe f=$(cat); printf '%%s\n' "$f"; ns=$(sed -n 's/^(ns \([^ )]*\).*/\1/p' %{buffer_name} | head -n 1); printf '%%s' "$f" | neorepl eval -s editor ${ns:+--ns "$ns"} - 2>&1 | sed 's/^/;; => /' '''
# Save and load the file.
l = [":write", ":sh neorepl load %{buffer_name}"]
```

## Editors with an nREPL client of their own

CIDER and the like connect to the same server, through the same port file,
and the REPL beside them sees what they evaluate. Going the other way,
`,edit NAME` in the REPL opens a definition in `$EDITOR`.
