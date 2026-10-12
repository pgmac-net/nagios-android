#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# A check whose output holds the characters Nagios' JSON writer gets wrong
# (pgmac-net/nagios-android#23). It makes Nagios write, in order:
#
#   a bell                             as \a, which JSON does not have
#   a vertical tab                     as \v, likewise
#   a backslash, then an a             as \\a: a valid escaped backslash, then a plain a
#   a backslash, then a bell           as \\\a: a valid escaped backslash, then the invalid escape
#
# Nagios takes one level of backslash off a plugin's output itself, so for one real backslash
# to reach the JSON the plugin has to print two: a single one is simply lost (measured, on
# 4.5.3). That is why the printf below has twice as many as the lines above.
#
# Run by the container's Nagios as the check for web01's "Control chars" service. Not a
# secret and not real: nothing here is read from anywhere.
printf 'Control characters: bell[\a] vt[\v] backslash-a[\\\\a] backslash-bell[\\\\\a] end\n'
exit 2
