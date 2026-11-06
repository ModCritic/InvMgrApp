#!/bin/bash
# Runs the command files the host drops in queue/, one at a time, inside this desktop session, so
# whatever they start has the session's display. Each N.sh is renamed N.run while it runs; its
# output lands in N.out and its exit code in N.rc, which is written last. A command that starts
# something long-lived has to put it in the background itself.
Q="$(dirname "$(readlink -f "$0")")/queue"
while :; do
    for f in "$Q"/*.sh; do
        [ -e "$f" ] || continue
        b="${f%.sh}"
        mv "$f" "$b.run"
        bash "$b.run" >"$b.out" 2>&1
        echo $? >"$b.rc.part"
        mv "$b.rc.part" "$b.rc"
    done
    sleep 0.1
done
