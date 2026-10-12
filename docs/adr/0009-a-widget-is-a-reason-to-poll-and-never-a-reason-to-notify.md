# A widget is a reason to poll a profile, and never a reason to notify

A profile is polled in the background while it has notifications on **or** a widget on the home screen. Having a widget does not turn notifications on: a profile polled only for its widget is fetched, the cache and the widget are brought up to date, and nothing is announced or remembered as announced.

Until M5 the two were one thing. M4 made "polled in the background" exactly "notifications are on" (ADR 0008, and the rule that the app makes no request the person did not ask for). A widget breaks that: it is only useful if what it shows is recent, and it has no screen open to cause a fetch (pgmac-net/nagios-android#7).

## Considered options

- **The widget shows whatever the cache has.** No new polling. A profile without notifications goes stale as soon as the app is closed, so the widget spends most of its life marked out of date. Honest, and not much of a widget.
- **Placing a widget requires notifications to be on.** One switch, one rule. It forces notifications on someone who only wants a number on their home screen, which is the opposite of "off until switched on".
- **A schedule and an interval per widget.** Two widgets on one profile could then disagree, and the profile's notifications might want a third value. One profile has one background job, so something would have to choose.
- **Either is a reason to poll; only one is a reason to notify (chosen).**

## Decision

- **Polled**: notifications on, or at least one widget bound to the profile. One job per profile, at the profile's own interval. Removing a profile's last widget stops its polling if its notifications are off.
- **Announcing**: notifications on, and nothing else. `PollSchedule` keeps the two apart, and `BackgroundPoller` runs the announcing half only for a profile that announces.
- **The interval is the profile's**, shown in the profile editor under "Background checks", always, and no longer inside Notifications. The widget's configuration screen states it. An interval hidden behind a switch that is off would be one nobody could see or change.
- **Placing a widget is asking.** The configuration screen says, before anything is chosen, that the profile will be checked in the background for as long as the widget is there, and that this does not turn notifications on. That keeps "no request the person did not ask for" true.
- **The Refresh button** on the largest layout polls the widget's profile once, now. It is the person asking, so it does not wait for the schedule or for a network.

## Consequences

- **Where #46 says a profile "stops being watched", it now means "stops announcing".** Switching notifications off takes that profile's notifications down and forgets what was announced, whether or not a widget keeps it polled. Removing a widget never touches notifications.
- **A widget-only profile has no announced memory.** If its notifications are switched on later, the first poll after that is a first poll: one summary. That is the same rule as before, arrived at from the other side.
- **A second cause of background requests exists**, and it is quieter than the first: there is no switch in the app that says "this profile is polled because of a widget". The configuration screen says it once. If that proves too easy to forget, the profile editor's "Background checks" part is where a line saying so belongs.
- **A widget that Android removed while the app could not be told** would keep its profile polled for ever. So at app start the app forgets every widget Android no longer lists, before the schedule is looked at.
- **Nothing here is about what a widget shows.** That is `WidgetModel`, from the cache, and has no opinion on who caused the poll.
