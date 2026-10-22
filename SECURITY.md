# Security

## Reporting a security problem
Please **don't** open a public issue. Use GitHub's private **Report a vulnerability** button
(Security tab of this repository), describe the problem and how to reproduce it, and give us time to fix it before sharing it.

## How the project is protected
- **Signed updates.** The app only installs an update whose manifest is signed with the owner's offline key
  (never stored on GitHub) and whose APK matches the signed SHA-256, downloaded only from this repository's releases.
  Even someone who took over this GitHub repository could not push an update to users.
- **Builds are locked down.** Every GitHub Action is pinned to an exact commit (not a movable tag), releases are
  published with GitHub's own `gh` CLI rather than a third-party action, and the APK signing keystore is shredded
  the moment the build step finishes, before anything else runs. Build logs are public (`ci-log` branch) and contain no secrets.
- **Manual installs can be verified.** `update/release.json` (signed) carries the SHA-256 of the current APK;
  docs/SETUP.md explains how to check a downloaded file against it.
- **Issues can't trigger anything.** No workflow in this repository runs on issues, issue comments, pull requests
  or `pull_request_target`. Builds only run on pushes by maintainers (`main` / `screens/**`) or by hand.
  `tools/check-workflows.sh` enforces this on every test run.
- **Reports are read as text only.** Issues are read by the maintainer (with help from Claude, Anthropic's AI) as
  information only: instructions, commands, code, links and attachments inside an issue are never run, opened,
  downloaded or followed. Nothing is changed, built, published or replied to because an issue asks for it;
  only the project owner decides that, and every public reply is approved by the owner.
