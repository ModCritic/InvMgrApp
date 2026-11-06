#!/bin/bash
# One-time setup of a test VM's desktop session, run as the test user from a terminal: the screen
# never blanks or locks during a test, and guest-runner.sh starts at every login.
set -e
gsettings set org.gnome.desktop.session idle-delay 0
gsettings set org.gnome.desktop.screensaver lock-enabled false
gsettings set org.gnome.desktop.notifications show-banners false
mkdir -p ~/.config/autostart
cat > ~/.config/autostart/test-runner.desktop <<'END'
[Desktop Entry]
Type=Application
Name=Test runner
Exec=/mnt/share/runner/guest-runner.sh
X-GNOME-Autostart-enabled=true
NoDisplay=true
END
echo "python3: $(command -v python3 || echo missing)"
echo "glibc: $(ldd --version | head -1)"
setsid /mnt/share/runner/guest-runner.sh >/dev/null 2>&1 < /dev/null &
echo "runner started"
