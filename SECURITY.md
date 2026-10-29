# Security policy

## Supported versions

The latest release on `main` receives fixes. Older tags do not.

## Reporting a vulnerability

Open a [private security advisory](https://github.com/YanIanZ/BravenSpawner/security/advisories/new)
rather than a public issue. Include:

- affected version and server software
- reproduction steps
- impact (item duplication, privilege escalation, remote crash, …)

You can expect an initial response within a week. Please allow time for a fix
before public disclosure.

## Scope

In scope:

- item duplication or storage loss
- permission bypass (`bravenspawner.*`)
- crashes reachable by a normal player
- SQL injection through configuration or storage keys

Out of scope:

- behaviour of other plugins
- servers running modified forks of this project
- issues that require operator-level access to reproduce
