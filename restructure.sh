#!/usr/bin/env bash
# Moves flat-named files into their real project paths (build machine only).
#   res__<folder>__<file>      -> app/src/main/res/<folder>/<file>
#   app__AndroidManifest.xml   -> app/src/main/AndroidManifest.xml
#   app__<Name>.kt             -> app/src/main/java/<Name>.kt
#   shared__<Name>.kt          -> shared/src/main/java/<Name>.kt
#   anything else with "__"    -> every "__" becomes "/"   (app__build.gradle.kts -> app/build.gradle.kts)
# Files without "__" stay where they are.
set -euo pipefail
shopt -s nullglob
for f in *__*; do
  [ -f "$f" ] || continue
  case "$f" in
    res__*)                 dest="app/src/main/res/${f#res__}"; dest="${dest//__//}"; dest="app/src/main/res/${dest#app/src/main/res/}" ;;
    app__AndroidManifest.xml) dest="app/src/main/AndroidManifest.xml" ;;
    app__*.kt)              dest="app/src/main/java/${f#app__}" ;;
    shared__*.kt)           dest="shared/src/main/java/${f#shared__}" ;;
    *)                      dest="${f//__//}" ;;
  esac
  mkdir -p "$(dirname "$dest")"
  mv "$f" "$dest"
  echo "moved: $f -> $dest"
done
echo "restructure done"
