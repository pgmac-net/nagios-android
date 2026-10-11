# A time sent to Nagios is written in a format learned from Nagios, and what it scheduled is read back

To send Nagios a time (a downtime window, a forced check), the app reads the server's date format and timezone offset from the command form Nagios itself serves, and after scheduling a downtime it reads the downtime back and compares it with what was asked. There is no date-format setting, and the format is never assumed.

Nagios' `cmd.cgi` takes times as text. It parses them with `sscanf` according to the server's `date_format` (US `MM-DD-YYYY`, European `DD-MM-YYYY`, or ISO) and turns them into a moment with `mktime` in the server's own timezone. Neither setting is reported anywhere in the JSON API. The failure is the worst kind: sending month and day the wrong way round is not rejected. 3 April is read as 4 March, a downtime lands on another day, and nothing says so. A downtime also suppresses notifications for its whole window, so a misplaced one is the most harmful mistake a command can make.

The form Nagios serves for a command comes pre-filled with "now", in exactly the format and timezone it will later parse. The JSON API gives the same moment as a number. Setting the two side by side gives the format and the offset with no setting for anyone to get wrong. Only one reading of the text can be the same moment as the number, because swapping day and month moves a date by weeks.

## Considered options

- **A date-format setting on the profile.** Simple to build. Most people do not know their server's `date_format`, the default guess is wrong for many installations, and a wrong setting fails in silence, which is the failure this exists to prevent.
- **Detect, without reading back.** Trusts the detection completely, and cannot notice the cases detection cannot see (below).
- **Avoid sending dates.** Not possible: a fixed downtime needs a start and an end, and even a forced check needs a start time.

## Consequences

- **A command that carries a time costs two requests before it** (the JSON clock and the form) **and, for a downtime, one after.**
- **On a day whose number equals its month (12 days a year) the two orders look the same.** The form's own pre-filled end time, two hours on, shows the order if that crosses midnight. Otherwise the app uses the format it remembered from an earlier day, stored on the profile. If it has never seen this server on another day, a date that reads the same either way is still sent, and for any other date nothing is sent and the user is asked once which order the server uses.
- **What the server shows today outranks what was remembered.** A server's setting can change; a remembered format is only a fallback for days that cannot show one.
- **A forced check never needs the format.** It wants "now", and sends the form's own "now" back as it came.
- **The offset is the server's offset now.** A window on the far side of a daylight-saving change will be an hour out. The app cannot know the server's zone rules. This is caught by the read-back, not prevented.
- **The read-back is what makes the rest safe to rely on.** A remembered format that is stale, a daylight-saving change, a server whose clock is wrong: each produces a downtime that is not where it was asked for, and each is reported as that, with both windows shown and an offer to cancel. This is also why cancelling a downtime is part of the same work.
- **A window that Nagios places in the past is accepted and then dropped.** It never appears, so the read-back reports it as not confirmed.
- Everything here rests on reading one HTML form. If a future Nagios changes that form, the app stops being able to send times and says so; it does not fall back to guessing.
