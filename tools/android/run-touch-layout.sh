#!/bin/bash
# Runs the desktop build with the touch layout forced on, which is the only way to look at the
# phone interface without a phone. See CLAUDE.md section 5 OD-5.
#
# NOT `mvn javafx:run -Dinvmgr.platform=android`: the javafx-maven-plugin forks a JVM carrying only
# the <options> in the pom, so a -D on the maven command line never reaches the app. Verified by
# reading the forked JVM's own command line out of `pgrep -af`.
#
# Every path below is derived rather than written out. The three that used to be hard-coded
# (/home/sandboxuser/.m2, /workspace/InvMgrApp and /usr/lib/jvm/current) were all container
# paths, and all three were wrong once the project moved to the host, which left this script
# broken in a way nothing would report until someone ran it.
M2="$HOME/.m2/repository/org/openjfx"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
FX=$M2/javafx-base/21/javafx-base-21-linux.jar:$M2/javafx-base/21/javafx-base-21.jar
FX=$FX:$M2/javafx-controls/21/javafx-controls-21-linux.jar:$M2/javafx-controls/21/javafx-controls-21.jar
FX=$FX:$M2/javafx-graphics/21/javafx-graphics-21-linux.jar:$M2/javafx-graphics/21/javafx-graphics-21.jar

exec "$JAVA" \
  -Dprism.forceGPU=true -Dprism.order=es2 \
  "-Dinvmgr.platform=${1:-android}" \
  --module-path "$FX" \
  --add-modules javafx.base,javafx.controls,javafx.graphics \
  -classpath "$REPO/target/classes" \
  com.modcritic.invmgr.Launcher
