# light-sdk

or: a tool for building Tools

## tl;dr

This repository contains the scaffolding for building simple tools for the Light Phone III. Included are a library ([`:sdk:client`](./sdk/client)) and placeholder application ([`:tool`](./tool)) that depends on it. 

## Important updates

### July 1, 2026

If you're reading this, welcome! You're early! (in a cool way)
This repo is a work-in-progress and will remain so for a while. Things are going to change _fast_ in the coming weeks. If you're going to start building right away, be sure to `git pull` frequently.

Before you do, though, please be aware that **while we feel good about letting everybody start to explore and build, we are still working on the infrastructure to properly deploy your new tools.**

The currently builds of LightOS in the wild are not yet ready to "play nice" with the tools built here. If you're someone who's already comfortable working with ADB to sideload APKs on your Light Phone III, you can totally do that with whatever you do here! But we're shooting to make these tools feel as seamless as the ones already available in LightOS, and that's going to take a bit more work. 

We're hoping to have an update later this month. In the meantime, the best way to start working is to use an Android emulator running our new [LightOS Emulator](sdk/emulator). To get started, see [System app](docs/system_app).

## About Light SDK development

All tools created with the Light SDK should host its application code within the `tool` module, using the primitives provided by the SDK client library.

They should also adhere to the following Android development best practices:

- All source code written in [Kotlin](https://kotlinlang.org/)
- UI components are built with [Jetpack Compose](https://developer.android.com/compose)
- [Coroutines](https://developer.android.com/kotlin/coroutines) are used for async programming
- Tool architecture follows [Model–view–viewmodel (MVVM)](https://en.wikipedia.org/wiki/Model%E2%80%93view%E2%80%93viewmodel) best practices

Although this repository looks like a standard Android development environment in many ways, you'll quickly find out that we have (gently but broadly) restricted which Android APIs and third-party libraries can be used. This is in an effort to provide a secure and distinctly _light_ experience for our users. These restrictions are _not_ set in stone and should ease up over time.

If there is a stable, open-source library that you'd like us to allow, [please let us know](LINK TO OPENING AN ISSUE)!

## Quick start

### Prerequisites

> [!NOTE]
> Currently, library builds are hosted as GitHub packages so each artifact lives alongside its source. However, this requires using a GitHub Personal Access Token (PAT).
>
> In the future, we plan on migrating to Maven Central to avoid this.

First, [create a GitHub Personal Access Token (PAT)](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens#creating-a-fine-grained-personal-access-token) with `Package` permissions set to **read-only**.

Then use one of the following methods to add your token locally:

- Using local environment variables:
    ```plaintext
    GITHUB_ACTOR=your_username
    GITHUB_TOKEN=your_token
    ```
- Using the `local.properties` file in this project:
    ```plaintext
    gpr.user=your_username
    gpr.key=your_token
    ```

### Running your Tool

**You can test your tool on any Android device or emulator**, but certain functionality (receiving push notifications, requesting special permissions) can only be tested with:

- Real Light Phone hardware running LightOS
- Running our LightOS emulator app as a system app on your computer. For detailed information, see([System app](docs/system_app).

You can quickly [create an emulator](https://developer.android.com/studio/run/managing-avds) that generally feels like an LPIII by using the following settings:

- 1080 X 1240, 3.92" display
- Android API 34
- No Google Play Services installed

### Building a tool

1. Fork or clone this repository.
2. Install Android Studio or IntelliJ IDEA and open this project.
3. Edit the code in `HomeScreen` and `HomeScreenViewModel` to get started. `Homescreen` surfaces a `@Composable` method named `Content`. This is the UI that is shown when the tool first boots. You'll notice this UI sources data from it's `viewModel` field, which is an instance of `HomeScreenViewModel`. Edit that class with your screen's logic and expose the data to the UI using either Compose `State` or Coroutine `Flow`s. If you want to create a new screen, create a new `ViewModel` pair: your screen should extend from `LightScreen` and your VM from `LightScreenViewModel`. Your screen implementation will need:
   1. A direct reference to your `ViewModel`'s class type
   2. A factory method for creating a new instance of your `ViewModel`.

Look at `HomeScreen` as an example for how this is done. To navigate to your new screen, use the `navigateTo` function built into `LightScreen` - just pass it a lambda to create an instance of your new screen. Note that the `LightScreen` constructor takes in a `SealedLightActivity`. The lambda is provided an instance of this as a default parameter.

Since LightOS does not use Android system navigation, we provide a back button for you. As long as you use `navigateTo` to move between screens, our back button should work great. If need be, you can override the `onBackPressed` method in your `LightViewModel`.

## About tool sharing

Currently, **there's no easy way to share your tools with other Light Phone III users**, but we're working hard on a solution and this is how we believe it's going to look:

### Current sharing experience

Given our relatively limited resources and desire to keep our users safe, we're requiring that all community tools be open source (including our own!). We will be building and signing these tools directly from a publicly available git commit, and we'll be archiving the source at build time. You're free to build and share privately, but LightOS won't let you install tools that are not signed by us without acknowledging privacy and performance risks. We won't block users from performing these "dangerous" sideloads, but we're not going to encourage it either. In the near future, you'll be able to queue up a build of your tool on our servers, and if it follows our guidelines and compiles cleanly, we will hand you back a signed, shareable APK.

### Future sharing experience

After we release a version of LightOS that supports community tools, users will have an option to choose what kind of tools they want to be able to run on their device:

| Tool Type | Description |
|-----------|-------------|
| **Light-approved tools** | These include tools that are either built internally by the Light team, or built by the community and officially tested/signed-off by the Light team. We don't know _exactly_ what that sign-off process is going to look like, but as a heads-up: we're going to be looking pretty hard at whether a submitted tool matches the Light ethos both functionally and aesthetically. We've included a UX/UI library to make this as easy as possible! From a technical standpoint, these approved tools are both signed by us _and_ added to an "allow-list" within LightOS. Phones with this option selected will only install and display tools that meet both criteria. |
| **SDK-built tools** | This is a slightly more permissive choice. Phones with this option selected will install and launch any tool that was built and signed by Light. These don't require any manual approval by us (though we can block them in extreme cases). If a user wants to be able to install a tool that was shared locally or somewhere outside of Light's dashboard, but they still want to be confident that it will run well and integrate nicely with LightOS, they might choose this option! |
| **Any tools** | A user will have the option to make any APK launchable from LightOS, but they will own the responsibility of getting them un/installed. When a user selects this option, we will be warning them that they are potentially opening their device up to security risks, and in doing so will limit our ability to support them if something goes wrong.|

## Additional resources

Looking for additional resources? Check out the rest of [our developer documentation(./docs).

