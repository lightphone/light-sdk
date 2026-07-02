# Declaring Tool Metadata

Every tool needs a handful of pieces of metadata: a unique id, a display name, a version, and a list of any permissions it will need to ask of LightOS. You declare all of these in a single file ([`tool/lighttool.toml`](../../tool/lighttool.toml)) and the SDK's build process will handle the job of injecting them into your compiled tool.

> [!NOTE]
> If you're familiar with Android development, you'll be used to putting this metadata into your `build.gradle` and/or `AndroidManifest.xml` files. This is still happening under the hood via our build plugin, we just wanted to create a single, simple place for tool devs to enter this info.

## The file

Drop a `lighttool.toml` at the root of your `tool/` module:

```toml
[tool]
id          = "com.example.mytool"             # Java package id, dotted, lowercase
label       = "My Tool"                        # Your tool's display name
versionCode = 1                                # monotonically-increasing integer
versionName = "1.0.0"                          # user-visible version string
permissions = ["android.permission.CAMERA"]  # allowlisted permissions only
```

## Fields

### `id` — your tool's unique identifier
A dotted, lowercase Java package identifier with at least two segments — e.g. `com.example.mytool`. Once a tool is published you can't change this without it being treated as a different tool, so pick something stable. If your tool is going in the Light tool library, this will need to be globally unique! We'll let you know if you're trying to pick one that's already been used.

### `label` — the name shown on the device
1–50 printable characters, no `<`, `>`, or control characters. This is what users see in the Light Phone launcher.

### `versionCode` — monotonically-increasing integer
Android refuses updates whose `versionCode` does not strictly exceed the installed one. The Light build server will reject a submission whose `versionCode` is not greater than the previous published build, so always bump this when shipping a new version.

### `versionName` — user-visible version string
Any combination of letters, digits, `.`, `_`, `+`, or `-`, up to 30 characters. This is the version string a user sees ("1.2.0", "2024.06", "0.3.0-rc1", etc.). It has no semantic meaning to Android beyond display — `versionCode` is what controls update ordering.

### `permissions` — Android permissions your tool needs
An array of permission strings, each one from the allowlist below. Anything not on the list will fail the build. Each entry becomes a `<uses-permission>` element in the generated manifest.

## Permission allowlist

To keep tools focused and the platform safe, only a curated set of permissions is currently accepted. The current list lives in [`LightToolMetadata.kt`](../../plugin/src/main/kotlin/com/thelightphone/plugin/LightToolMetadata.kt) under `LightToolPolicy.ALLOWED_PERMISSIONS` (though keep in mind that a user's current install of LightOS may have a slightly different list!). If you need a permission that isn't on the list, please get in touch — we're happy to expand the list when there's a real use case for it.

