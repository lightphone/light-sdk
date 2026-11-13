# Contributing

**The [code of conduct](CODE_OF_CONDUCT.md) applies to all contributions, please go check that out first.**

### Limitations
While the software in this library is fully open source, it is depended upon by the Light Phone's existing products. Above all else, we need to maintain compatibility with those products so we can continue to deliver safe, timely, and functional updates to our customers. We are excited to pull more closed code from those products into our open repositories, but it will take time. Ultimately, this means we are currently not interested in certain types of contributions from the community:
* Public API changes
* Additional/updated third-party dependencies (we'll do our best to stay up-to-date)
* Meaningful architectural changes

### Welcome Contributions

We expect contributions to come in the form of GitHub [issues](https://github.com/lightphone/light-sdk/issues) and [pull requests](https://github.com/lightphone/light-sdk/pulls). Not every issue requires a pull request, but we will close any pull requests that are not associated with an existing issue. If you are interested in submitting code changes for your issue, please state that clearly. Someone from the Light team will explicitly indicate on an issue that we would welcome a relevant PR. **We reserve the right to politely refuse any proposed work. If having your work merged is important to you, please wait until we give a green light on your issue!**

We expect _all_ modules in this repository to compile, and _all_ tests to pass for each PR. We will have an automated check that runs on GitHub, but to save time/resources, **please** check that this is true before opening your PR. For this repo, you can run `./gradlew check` in the root directory.

Types of issues we are excited to receive:
* **Bug Reports** (something does not work as it is intended to)
  * Please include the commit on which you are experiencing the issue, a description, and detailed reproduction steps.
* **Feature Requests** ("it would be helpful if this software could also do `X`")
  * This includes requests to add open source third-party libraries or Android APIs to the build [plugin's](plugin) allow-list!
  * New features should be relevant to a meaningful percentage of Light Phone users / consumers of this software. We reserve the right to make the final call on whether or not this is true for your issue.
* **Security Issues** (something in this software might allow a bad actor to degrade a Light Phone user's experience or violate their privacy)
* **_Material_ Performance Improvements** (something in this software is actively degrading a Light Phone user's experience, or is egregiously consuming resources)

### AI/LLM Policy
(Adapted from [Astral's](https://github.com/astral-sh/.github/blob/main/AI_POLICY.md))

We like talking to _people_!

- We expect all communication in this repository to come from a human. That includes issue/PR descriptions, comments, and replies. If you are a non-native English speaker using an LLM to translate for you, we would be grateful if you included your original content alongside the translation.
- We expect you to be able to explain any proposed code changes in your own words. 
- We find that code comments produced by LLMs tend to be overly verbose and/or specific to your dev session. Please delete them, or if you think they're genuinely useful, make sure they are brief and in your voice.
- **You are responsible for any code or other communication that comes from your account**.
- **We (the humans on the Light dev team) are responsible for any code that gets merged.**

### Releases

Merging a PR into `main` that touches SDK code (`sdk/`, `plugin/`, `tool/`, `builder/`, `lint-rules/`, `signer/`, `trust-format/`, `gradle.properties`, `gradle/libs.versions.toml` or the root `build.gradle.kts` / `settings.gradle.kts`) releases a new version automatically: the [release workflow](.github/workflows/release.yml) bumps `sdkVersion`, tags `vX.Y.Z`, publishes to GitHub Packages and creates a GitHub Release. CircleCI then builds the builder image from the tag.

The bump defaults to patch. Pick another with a PR label:

| Label | `0.2.4` becomes |
|---|---|
| none | `0.2.5` |
| `release:minor` | `0.3.0` |
| `release:major` | `1.0.0` |
| `no-release` | no release; the change ships with the next one |

When several PRs are merged before a release, the largest bump among them wins. The "Release Preview" check on each PR shows which version merging it would release.

Don't edit `sdkVersion` in a PR unless you need a specific version: a value above the computed one is released as-is.

To release without a PR, or to fix a missing label, run the workflow manually (Actions → Release → Run workflow) with a `bump`; `dry_run` computes the version and runs `./gradlew check` without releasing. If publishing or the GitHub Release fails after the tag is pushed, use "Re-run failed jobs" on that run.

#### One-time setup

The workflow pushes to `main` through a GitHub App, since the branch ruleset requires PRs:

1. Create a GitHub App owned by the organization with repository permissions **Contents: Read and write** and **Actions: Read and write**, no webhook.
2. Install it on `lightphone/light-sdk` only.
3. Add the app to the bypass list of the `main` ruleset.
4. Add the repository secrets `RELEASE_APP_CLIENT_ID` (the app's client ID) and `RELEASE_APP_PRIVATE_KEY` (a generated private key).
5. Create the labels `release:minor`, `release:major` and `no-release`.
