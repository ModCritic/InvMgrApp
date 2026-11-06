#!/usr/bin/env bash
# Stops every running InvMgr launched from an installed copy, found by the program file each
# process runs rather than its command line: `pkill -f` matches any shell whose command line
# merely mentions the path, the one running it included.
for exe in /proc/[0-9]*/exe; do
    target="$(readlink "$exe" 2>/dev/null)" || continue
    case "$target" in
        */bin/InvMgr) pid="${exe#/proc/}"; pid="${pid%/exe}"; echo "stopping $pid ($target)"; kill "$pid" ;;
    esac
done
sleep 2
