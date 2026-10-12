# A notification is for a HARD problem that is new or worse, and what was announced is kept in the disposable cache

Background polling (M4) needs one rule for what a poll is worth saying and one place to remember what has been said. The rule is a plain function, `Announcer.decide(state, outcome, settings)`, with no Android and no clock in it. The memory is two tables in `status.db`, the database that is wiped whenever its schema changes.

Both are easy to get wrong in ways that only show on a phone at night, and neither is obvious from the code alone, so the reasoning is here (pgmac-net/nagios-android#6).

## What is announced

- **Only a HARD problem.** A SOFT problem is Nagios still retrying; announcing it would mean a notification for every blip that clears on the second attempt. Nagios does not notify for those either.
- **Whether or not Nagios would notify.** A service with notifications switched off in Nagios is still announced. The app shows what is wrong; who Nagios would have told is a different question.
- **New, and again when it gets worse.** Worse follows the order of `Severity`: a host down before a service, CRITICAL before WARNING before UNKNOWN. A problem that gets better without recovering brings its notification up to date and does not interrupt.
- **The first successful poll is one summary.** The person has just looked at that list in the app. Those problems count as known from then on, but none has a notification of its own, so none produces a recovery notice later.
- **More than five at once is one summary.** A switch failing should not be forty notifications. Five or fewer are raised one by one.
- **Unhandled only, unless the profile says otherwise.** A problem that becomes handled (acknowledged, in downtime, or under a host that went down) has its notification taken away without a word. That is not a recovery, and it is not said as one.
- **A recovery is said only of something that had its own notification.** It replaces that notification and never interrupts. Something that was only ever part of a summary recovers silently.
- **A failed poll says nothing about problems.** It never changes what is known, cannot announce a recovery, and can never amount to "all OK".

## When Nagios cannot be polled

Failures are split by one question: may this pass by itself?

- **It may** (no answer, a certificate problem, a captive portal, a server error, Android's local network protection): worth a notification after three failed polls in a row, and only if the profile's "Tell me when this Nagios can't be reached" is on. Someone whose Nagios is only reachable at home will fail every poll while they are out, by design, and can switch that off.
- **It will not** (a rejected password, a rejected Access token, credentials the app cannot read, a URL the app will not use): worth a notification at the first failure, and regardless of that switch. Alerts have stopped and waiting will not bring them back.

One notification per outage, worded by cause. If the cause changes, the notification changes quietly. The next successful poll removes it.

## Where it is remembered

What was announced is recorded per profile: each known problem with its severity and whether it has a notification of its own, plus whether the first poll is done, the count of failures in a row, and the outage announced. It records what was *announced*, not what is still showing, so swiping a notification away never causes a repeat.

### Considered options

- **Ask Android which notifications are showing.** Nothing to store. But a swiped notification looks the same as one never posted, so every dismissed problem would come back at the next poll.
- **A table in `profiles.db`.** Survives everything. Every change to its shape would then need a strict migration and a test, on the database that holds people's credentials, for data that describes a moment that has already passed.
- **DataStore.** No schema at all, but the list is unbounded in principle and is replaced wholesale at every poll, alongside a poll that is already one Room transaction.
- **Tables in `status.db` (chosen).**

### Consequences

- **This bends ADR 0005, which says the cache holds only what can be fetched again.** The memory cannot be fetched; it can be rebuilt. If `status.db` is wiped (a schema change in an update, or the person clearing storage), the next poll is a first poll and is summarised. The cost is one summary where there might have been individual notifications, and no recovery notices for problems that were showing at that moment. The rule for `status.db` is now "nothing whose loss costs more than that".
- **A summary after an update is expected, not a bug.** Raising `StatusDatabase.VERSION` causes one for every profile with notifications on.
- **A name written by another version of the app is dropped, not a crash.** Severities and outage causes are stored by name. An unknown one is skipped on load; at worst that one problem is announced again.
- **Deleting a profile forgets what was announced for it**, with the rest of its cache, and the startup sweep covers the same tables.
- **The rules can be tested without a phone.** Everything that makes a notification right or wrong is in `Announcer`. How a notice looks, which channel it uses and what a tap does are decided elsewhere and can change without touching it.
