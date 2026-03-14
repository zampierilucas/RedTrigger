# RedTrigger justfile
# Usage: just build, just ship, just copy, just version, just bump-patch, just bump-minor

android_home := env("ANDROID_HOME", env("HOME", "") / "android-sdk")
java_home := env("JAVA_HOME", "/usr/lib/jvm/java-17-openjdk-amd64")
storage := env("REDTRIGGER_OUTPUT", "/tmp")

# Build debug APK
build:
    cd {{justfile_directory()}} && \
    ANDROID_HOME={{android_home}} JAVA_HOME={{java_home}} \
    ./gradlew assembleDebug --no-daemon --no-configuration-cache

# Get the built APK path
apk_path := justfile_directory() / "app/build/outputs/apk/debug"

# Copy APK to shared storage
copy: build
    @APK=$(find {{apk_path}} -name "RedTrigger-*.apk" | head -1) && \
    cp "$APK" {{storage}}/redtrigger-debug.apk && \
    echo "Copied to {{storage}}/redtrigger-debug.apk ($(du -h {{storage}}/redtrigger-debug.apk | cut -f1))"

# Build + copy + print version
ship: copy
    @grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/v\1/'

# Get current version
version:
    @grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/'

# Bump patch version (1.5.0 -> 1.5.1)
bump-patch:
    #!/usr/bin/env bash
    current=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
    code=$(grep 'versionCode' app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
    IFS='.' read -r major minor patch <<< "$current"
    new="$major.$minor.$((patch + 1))"
    newcode=$((code + 1))
    sed -i "s/versionCode = $code/versionCode = $newcode/" app/build.gradle.kts
    sed -i "s/versionName = \"$current\"/versionName = \"$new\"/" app/build.gradle.kts
    echo "$current → $new (code $code → $newcode)"

# Bump minor version (1.5.0 -> 1.6.0)
bump-minor:
    #!/usr/bin/env bash
    current=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
    code=$(grep 'versionCode' app/build.gradle.kts | head -1 | sed 's/[^0-9]//g')
    IFS='.' read -r major minor patch <<< "$current"
    new="$major.$((minor + 1)).0"
    newcode=$((code + 1))
    sed -i "s/versionCode = $code/versionCode = $newcode/" app/build.gradle.kts
    sed -i "s/versionName = \"$current\"/versionName = \"$new\"/" app/build.gradle.kts
    echo "$current → $new (code $code → $newcode)"

# Clean build artifacts
clean:
    cd {{justfile_directory()}} && \
    ANDROID_HOME={{android_home}} JAVA_HOME={{java_home}} \
    ./gradlew clean --no-daemon

# Git commit and tag
tag message="":
    #!/usr/bin/env bash
    version=$(grep 'versionName' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
    msg="${1:-v$version}"
    cd {{justfile_directory()}}
    git -c commit.gpgsign=false add -A
    git -c commit.gpgsign=false commit -m "$msg"
    git tag "v$version"
    echo "Tagged v$version"
