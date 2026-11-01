# Publishing with JitPack

The repository publishes one Android release AAR, a sources JAR, a Maven POM and
Gradle Module Metadata. It uses Gradle's built-in `maven-publish` plugin and the
Android release component, preserving API/runtime dependency scopes. Test
dependencies are not published. No application module, credentials, signing,
or Maven Central configuration is required by this setup.

## Coordinates and versions

The single publication uses:

```text
com.github.tu-konnichiwa:AndroidPurchaseKit:<tag-or-commit>
```

The Gradle module remains `:android-purchase-kit`. The Maven artifact is named
`AndroidPurchaseKit` to match the GitHub repository and JitPack's single-artifact
coordinates. JitPack passes its `GROUP` and `VERSION` environment values as Gradle
properties in `jitpack.yml`. Local builds default to group
`com.github.tu-konnichiwa` and version `1.0.0`.

## Verify locally

Use the checked-in wrapper with a compatible launcher JDK:

```sh
./gradlew --no-daemon build publishToMavenLocal \
  -Pgroup=com.github.tu-konnichiwa \
  -Pversion=jitpack-check \
  -Dmaven.repo.local=/tmp/android-purchase-kit-jitpack-maven
```

This runs tests and lint and installs the publication into a temporary repository.
Inspect `com/github/tu-konnichiwa/AndroidPurchaseKit/jitpack-check/` under that
directory for the AAR, sources, POM and `.module` metadata. Omitting
`-Dmaven.repo.local` installs into the normal local Maven repository instead.

`jitpack.yml` uses Java 21 to launch Gradle. The existing daemon JVM criteria in
`gradle/gradle-daemon-jvm.properties` select and provision JDK 25 for the build.
The wrapper and catalog are unchanged. JitPack needs network access to provision
the daemon JDK, resolve dependencies and obtain Android SDK 37 if not preinstalled.

## Release

1. Commit the library and publishing configuration and push them to
   `https://github.com/tu-konnichiwa/AndroidPurchaseKit`.
2. Create and push the immutable first-release tag `1.0.0` on the tested commit.
3. Open [the JitPack project page](https://jitpack.io/#tu-konnichiwa/AndroidPurchaseKit),
   look up that tag and request its build.
4. Confirm that the remote build succeeds and inspect its artifacts before using
   `implementation("com.github.tu-konnichiwa:AndroidPurchaseKit:1.0.0")` in an app.

A successful local publication does not confirm the remote JitPack build. For a
private GitHub repository, configure repository access in JitPack and consumer
authentication separately; keep tokens out of committed files.

Configuration follows the official [JitPack Android guide](https://docs.jitpack.io/android/),
[JitPack build environment documentation](https://docs.jitpack.io/building/), and
[Android publication variant documentation](https://developer.android.com/build/publish-library/configure-pub-variants).
