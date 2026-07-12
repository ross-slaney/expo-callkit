# Public npm release process

Publishing is intentionally unavailable from ordinary pushes and pull
requests. The workflow runs only for a non-draft, non-prerelease GitHub release
whose tag, package version, changelog, and checkout commit all agree.

## One-time repository setup

1. Confirm the npm account or organization owns the `@ross-slaney` scope and
   can create `@ross-slaney/expo-callkit` as a public package.
2. Create a GitHub environment named `npm`, add required reviewers, and prevent
   untrusted branches from deploying to it.
3. For the bootstrap publish, add an environment secret named `NPM_TOKEN`. The
   package does not exist yet, so use a short-lived npm granular read/write
   token restricted to the `@ross-slaney` scope with bypass-2FA enabled. Never
   commit it to `.npmrc` or expose it to pull-request jobs.
4. Add a GitHub tag ruleset for `v*` that restricts creation and prevents tag
   updates/deletion. Enable immutable releases if the repository setting is
   available; until then, do not describe release tags as immutable.
5. Require every job in the repository CI workflow on `main` before a release
   can be cut.

npm now supports tokenless trusted publishing, which is preferable after the
package exists on npm. The first public release uses `NPM_TOKEN` to bootstrap
the package; provenance is still generated through GitHub's OIDC identity
token. Migrate the protected environment to trusted publishing after that
first release, update `publish.yml` to remove `NPM_TOKEN`/`npm whoami`, then
revoke the bootstrap token.

## Cut a release

`v0.1.0` already exists as a GitHub Packages release and must not be reused.
The current public-release candidate is already prepared as `0.2.0`, with its
dated changelog heading. Validate it on a normal reviewed branch:

```sh
npm ci
npm run typecheck
npm test -- --ci --runInBand
npm run lint
npm run verify:package
swift test
gradle --project-dir native-tests/android test
```

Merge that version/changelog change to `main`, then create a GitHub release
tagged exactly `v0.2.0` at the merge commit. Do not create or move the tag
before the version commit lands. Approve the protected `npm` environment only
after inspecting the workflow's package manifest and test results. For later
releases, bump `package.json`/`package-lock.json` and move the release notes to
a dated `## <version> - YYYY-MM-DD` heading before creating the matching tag.

The publish job then:

1. checks out the release tag with full history;
2. before executing repository scripts, proves the tag commit is on
   `origin/main` and that all three required CI jobs succeeded for that commit;
3. pins Node 20.19.4 and npm 11.18.0;
4. rebuilds and runs TypeScript/Jest/lint/package validation;
5. proves the tag, version, changelog, and checkout commit match;
6. refuses to continue if that version already exists on npm;
7. verifies the bootstrap `NPM_TOKEN` with `npm whoami`; and
8. runs `npm publish --access public --provenance`.

## Verify after publishing

```sh
npm view @ross-slaney/expo-callkit@<version> --registry=https://registry.npmjs.org
npm install @ross-slaney/expo-callkit@<version>
npm audit signatures
```

Confirm npm displays public visibility and a provenance link to the correct
GitHub release commit. Install into a clean Expo SDK 54 app and repeat both
native builds before announcing the release.

If a release is bad, publish a corrected patch and deprecate the bad version;
do not overwrite tags or attempt to republish the same immutable npm version.
