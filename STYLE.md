# Style guide

Apply this guide to Kotlin, Gradle, and Compose changes. It intentionally
records project conventions rather than generic Kotlin formatting rules.

## Kotlin and Gradle

- Keep platform-independent logic in `:shared`; use Android APIs only in
  `:app`.
- Put repository contracts in `shared/.../repository/Repositories.kt` and bind
  Android implementations with Hilt.
- Keep all dependency versions in `gradle/libs.versions.toml` and use the
  generated `libs.*` accessor in Gradle files. Do not add inline version
  strings.
- Add or update focused tests with behaviour changes. Pure logic belongs in
  `shared/src/commonTest`; Android/database behaviour belongs in `app/src/test`.
- Make SQLDelight schema changes in `.sq` files and regenerate the database
  interface before referring to generated APIs.

## Compose accessibility

The visible UI and audio interface must work together for a blind user.

- Announce meaningful state transitions through the app's output path; use
  `Modifier.semantics { liveRegion = LiveRegionMode.Polite }` for live
  announcement nodes.
- Keep controls at least 48dp and verify altered flows with TalkBack.
- Do not add `contentDescription` when visible direct-child text already names
  the control. A description on a container replaces the accessible names of
  its children.
- For repeated ambiguous controls, use only the visible label plus the minimum
  disambiguator (for example, `"Delete $name"`).
- Let native semantics announce selected/checked state. When a separate label
  and `Switch`/`Checkbox` need one node, wrap them in `toggleable` or
  `selectable` and make the child control passive (`onCheckedChange = null`).
- Every necessary `contentDescription` needs an adjacent
  `// a11y: <reason>` comment explaining information that visible text and
  native semantics cannot convey. The custom detekt rule enforces this.

Consult the architecture skill for the required output route and its silence
policy before changing spoken or live-region behaviour.
