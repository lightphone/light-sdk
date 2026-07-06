# light-sdk
or: a tool for building Tools

## tl;dr
This repository contains the scaffolding for building simple tools for the Light Phone III. Included are a library ([:sdk:client](./sdk/client)) and placeholder application ([:tool](./tool)) that depends on it. To create a tool that is fully compatible with LightOS, you must write your application code within the `tool` module, using the primitives provided by the sdk client library.

You can and should use current Android best practices: Kotlin for all source code, Compose for UI, Coroutines for async programming, and MVVM architecture. **Although this is appears to be a fairly standard Android dev environment, you will quickly find out that we are (gently but broadly) restricting which Android APIs and third-party libraries can be used. This is in an effort to provide a secure and distinctly _light_ experience for our users. These restrictions are _not_ set in stone and should ease up over time. If there is a stable, open-source library that you'd like us to allow, please let us know! More on this later.**

## IMPORTANT!! July 1, 2026 Update
If you're reading this, welcome! You're early! (in a cool way)
This repo is a work-in-progress and will remain so for a while. Things are going to change _fast_ in the coming weeks. If you're going to start building right away, be sure to `git pull` frequently.
Before you do, though, please be aware that **while we feel good about letting everybody start to explore and build, we are still working on the infrastructure to properly deploy your new tools.**
The currently builds of LightOS in the wild are not yet ready to "play nice" with the tools built here. If you're someone who's already comfortable working with ADB to sideload APKs on your
Light Phone III, you can totally do that with whatever you do here! But we're shooting to make these tools feel as seamless as the ones already available in LightOS, and that's going to take a bit more work. 
We're hoping to have an update on that front later this month. In the meantime, the best way to start working is to use an Android emulator running our new [LightOS Emulator](sdk/emulator). The instructions for getting that up and running
are [right here](docs/system_app).

## Quickstart
### Grabbing a token
We're currently hosting our library builds with GitHub Packages so each artifact can live beside its source. The tradeoff is that you'll need to add a GitHub token with package read access to your local build environment. **We are considering migrating to Maven Central to avoid this requirement when everything goes public.**
For now, you can either add environment variables with your username and token:
```
GITHUB_ACTOR=your_username
GITHUB_TOKEN=your_token
```
or you can add them to your `local.properties` file:
```
gpr.user=your_username
gpr.key=your_token
```

### Running your Tool
**You can test your tool on any Android device or emulator**, but certain functionality (receiving push notifications, requesting special permissions) can only be tested with:
A) Real Light Phone hardware running LightOS
B) An Android emulator (on your computer) set up to run our LightOS emulator app as a _system app_ ([see advanced instructions](docs/system_app))

You can quickly [create an emulator](https://developer.android.com/studio/run/managing-avds) that generally feels like an LPIII by using the following settings:
* 1080 X 1240, 3.92" display
* Android API 34
* NO Google Play Services installed

### Start Building
1. Fork and/or clone this repository into your local dev environment.
2. Install Android Studio and open this project within it. (IntelliJ IDEA should also work)

3. Edit the code in `HomeScreen` and `HomeScreenViewModel` to get started. `Homescreen` surfaces a `@Composable` method named `Content`. This is the UI that is shown when the tool first boots. You'll notice this UI sources data from it's `viewModel` field, which is an instance of `HomeScreenViewModel`. Edit that class with your screen's logic and expose the data to the UI using either Compose `State` or Coroutine `Flow`s. If you want to create a new screen, create a new Screen/ViewModel pair: your screen should extend from `LightScreen` and your VM from `LightScreenViewModel`. Your screen implementation will need:
   1. A direct reference to your ViewModel's class type
   2. A factory method for creating a new instance of your ViewModel.

Look at `HomeScreen` as an example for how this is done. To navigate to your new screen, use the `navigateTo` function built into `LightScreen` - just pass it a lambda to create an instance of your new screen. Note that the `LightScreen` constructor takes in a `SealedLightActivity`. The lambda is provided an instance of this as a default parameter.

Since LightOS does not use Android system navigation, we provide a back button for you. As long as you use `navigateTo` to move between screens, our back button should work great. If need be, you can override the `onBackPressed` method in your `LightViewModel`.

### Sharing Your Tool
**As of July 1, 2026, there's no "easy" way to share your tool with a Light Phone III user. We're working hard on that. This is how we believe it's going to look.**

Given our relatively limited resources and desire to keep our users safe, we're requiring that all community tools be open source (including our own!). We will be building and signing these tools directly from a publicly available git commit, and we'll be archiving the source at build time. You're free to build and share privately, but LightOS won't let you install tools that are not signed by us without acknowledging privacy and performance risks. We won't block users from performing these "dangerous" sideloads, but we're not going to encourage it either. In the near future, you'll be able to queue up a build of your tool on our servers, and if it follows our guidelines and compiles cleanly, we will hand you back a signed, shareable APK.

Once we release a version of LightOS that supports community tools, users will have an option to choose what kind of tools they want to be able to run on their device:
- **Light-approved tools**: These include tools that are either built internally by the Light team, or built by the community and officially tested/signed-off by the Light team. We don't know _exactly_ what that sign-off process is going to look like, but as a heads-up: we're going to be looking pretty hard at whether a submitted tool matches the Light ethos both functionally and aesthetically. We've included a UX/UI library to make this as easy as possible! From a technical standpoint, these approved tools are both signed by us _and_ added to an "allow-list" within LightOS. Phones with this option selected will only install and display tools that meet both criteria.
- **SDK-built tools**: This is a slightly more permissive choice. Phones with this option selected will install and launch any tool that was built and signed by Light. These don't require any manual approval by us (though we can block them in extreme cases). If a user wants to be able to install a tool that was shared locally or somewhere outside of Light's dashboard, but they still want to be confident that it will run well and integrate nicely with LightOS, they might choose this option!
- **Any tools**: A user will have the option to make any APK launchable from LightOS, but they will own the responsibility of getting them un/installed. When a user selects this option, we will be warning them that they are potentially opening their device up to security risks, and in doing so will limit our ability to support them if something goes wrong.

## [Complete Documentation](./docs)
