# Pages from `cmd.cgi`

What Nagios Core's `cmd.cgi` answers with. `CommandPage` reads these; they are how its reading is tested.

Captured from the throwaway test Nagios (`scripts/test-nagios`), Nagios Core 4.5.3, by sending each
command for real. Everything in them is made up: the hosts, the users, the output. Nothing here came
from anyone's real Nagios, and nothing needs sanitising.

- `form_*`: the page shown before a command is committed. The downtime and check forms are pre-filled
  with the server's "now", which is how the app learns a server's date format and timezone. There is
  one for each `date_format` setting.
- `result_*`: the page that answers a commit.
- `*_synthetic.html`: **not captured.** These need a Nagios configured to refuse (external commands
  off, authentication off, an unwritable command file). They are a captured page with the message
  replaced by the wording in `cgi/cmd.c` of Nagios Core 4.5.9.

To capture again: `scripts/test-nagios/start.sh --date-format ... --timezone ...`, then request
`cmd.cgi` as the `operator`, `viewer` or `limited` user.
