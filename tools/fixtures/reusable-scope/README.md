# `reusable-scope` — the fixture behind `curation-check.mjs --selftest`

A miniature INGenious workspace whose only job is to hold the counter-examples for one
question: **does the curation check find a Baustein the same way the engine does?**

The layout is the real one, because the shared space is defined relative to it:

```
Projects/ScopeProbe/          the project under test
Shared/SharedReusableComponents/   the app-level shared space, two levels up from the project
```

`Project.getSharedReusableComponentsPath()` builds `<appRoot>/Shared/SharedReusableComponents`
from the running install's `user.dir`; projects live at `<appRoot>/Projects/<Name>`. So from
a project root the shared space is `../../Shared/SharedReusableComponents` — the same
two-levels-up rule `curation-check.mjs` already used for the shared Object Repository.

## The six test cases, and what each one is a counter-example to

| Test case | Shape | Leaves | What it would catch |
|---|---|---|---|
| `Unscoped` | `Execute` · `Kundensuche:PartnerOeffnen` | 3 | the legacy call must keep working |
| `Projektweit` | `Execute` · `[Project] Kundensuche:PartnerOeffnen` | 3 | splitting the Action on its first colon reads the group as `[Project] Kundensuche` and finds nothing |
| `Gemeinsam` | `Execute` · `[Shared] Gemeinsam:Anmelden` | 2 | the shared space was never searched at all |
| `Gemeinsam ueber Reference` | `Execute` · `Gemeinsam:Anmelden`, Reference `[Shared]` | 2 | `TestStep.getEffectiveReusableRef()` takes the scope from the Reference cell when the Action carries none |
| `Verschachtelt` | `Execute` · `[Project] Kundensuche:KundeOeffnen` | 4 | a Baustein calling a Baustein, one scoped call inside another |
| `Kein Execute` | Object `Suchfeld` · Action `Kundensuche:PartnerOeffnen` | 1 | **the other direction**: `TestStep.isReusableStep()` requires Object `Execute`. The engine does not run this as a call, so the check must not expand it either |
| `Nicht auffindbar` | `Execute` · `[Project] Kundensuche:GibtEsNicht` | 1 + warning | an Execute step naming nothing is a broken test case, not a leaf step |

`PartnerOeffnen` is YAML and `KundeOeffnen` is CSV on purpose: INGenious 3.1 rewrites a
project from CSV to YAML on the first engine run, so both shapes exist in the wild and both
have to resolve.

Nothing here talks to a browser, an application, or Azure DevOps.

    node tools/curation-check.mjs --selftest
