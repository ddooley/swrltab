SWRLTabCLI
==========

SWRLTabCLI is a headless command-line add-on to [SWRLTab](https://github.com/protegeproject/swrltab) for running SWRL inference and SQWRL queries without a GUI.  It was developed by Damion Dooley at the [Centre for Infectious Disease Genomics and One Health (CIDGOH)](https://cidgoh.ca) at Simon Fraser University.

Build the fat JAR first (only needed once, or after code changes):

    mvn clean package

A wrapper script is provided for convenience:

    ./swrltabcli [options] <ontology-file>

OWLAPI detects the ontology format from file content rather than extension. Commonly used extensions:

| Extension(s) | Format |
|---|---|
| `.owl`, `.rdf`, `.xml` | RDF/XML |
| `.ofn` | OWL Functional Syntax |
| `.owx` | OWL/XML |
| `.omn` | Manchester OWL Syntax |
| `.ttl`, `.turtle` | Turtle |
| `.n3` | Notation 3 |
| `.nt` | N-Triples |
| `.jsonld` | JSON-LD |
| `.obo` | OBO Format |

### Modes (exactly one required)

| Option | Description |
|---|---|
| `--infer` | Fire all SWRL rules; print inferred axioms as OWL Functional Syntax |
| `--rules <name[,name…]>` | Fire one or more named SWRL rules; print inferred axioms as OWL Functional Syntax. Accepts a comma-separated list (e.g. `--rules S2,S4,S7`) and/or may be repeated (e.g. `--rules S2 --rules S4`). Rules fire and display in the order specified. |
| `--rule-text <swrl>` | Run an inline SWRL rule expression; print inferred axioms as OWL Functional Syntax |
| &nbsp;&nbsp;`--rule-text-name <name>` | Name to assign to the inline rule (default: `cli-rule`). Only applies with `--rule-text`. |
| `--delete` | Delete the rules/queries named by `--rules` from the ontology instead of firing them. Requires `--rules` and `--save` to persist the change. |
| `--query <name>` | Run a named SQWRL query stored in the ontology |
| `--query-text <sqwrl>` | Run an inline SQWRL expression |
| &nbsp;&nbsp;`--query-name <name>` | Name to assign to the inline query result set (default: `cli-query`). Only applies with `--query-text`. |
| `--list-rules` | List all SWRL rules with active/inactive status. With `--format txt`: emits plain `.swrl` file syntax that can be redirected straight to a file, edited, and fed back in with `--file` (see developer iteration workflow below). With `--format markdown`: emits a `<pre>` block with variables coloured by entity type; add `--no-color` for spans-free output. |
| `--list-queries` | List all SQWRL queries stored in the ontology. Supports the same `--format txt` and `--format markdown` options as `--list-rules`. |

### Input options

| Option | Description |
|---|---|
| `--file <path>` | Load SWRL rules and/or SQWRL queries from a text file into the ontology before running. May be repeated to load from multiple files. Works alongside any mode. If a loaded rule or query shares a name with one already in the ontology, the existing one is replaced. |
| `--save` | After all `--file` inputs have been loaded, write the updated ontology back to the original file in the same format. Requires at least one `--file`. Because OWLAPI serialises the entire ontology, any comments or non-standard formatting in the original file will not be preserved. |

### Output options

| Option | Description |
|---|---|
| `--format tsv\|csv\|markdown\|txt` | Output format (default: `tsv`). `tsv` and `csv` apply to query results. `markdown` renders `--debug` evaluation reports as markdown tables, and renders `--list-rules`/`--list-queries` as a `<pre>` block in `.swrl` file syntax with variables coloured by entity type. `txt` renders `--list-rules`/`--list-queries` as plain-text `.swrl` file syntax (no HTML, no colour spans) — output can be piped directly to a file and used as `--file` input. |
| `--no-color` | Disable per-argument HTML colour spans in `--format markdown` output. Applies to both `--debug` evaluation tables and `--list-rules`/`--list-queries`; with listing modes, `--no-color` produces spans-free output that can be pasted directly into a `--file` input. |
| `--output-file <path>` | Write inferred axioms to this file in OWL Functional Syntax instead of stdout. Applies to `--infer`, `--rules`, and `--rule-text`. The file is always written even when no axioms were inferred (producing an empty ontology), so it can be unconditionally referenced as an `owl:imports` target. |
| `--output-iri <iri>` | Stamp the output ontology with this IRI (e.g. `http://example.org/inferred.ofn`). Required for the file to be usable as an `owl:imports` target. Only meaningful with `--output-file`. |
| `--config <path>` | Load a custom `swrltab_config.yaml` from the given path instead of (or in addition to) the default locations. |
| `--ignore-imports` | Silently skip unresolvable `owl:imports` declarations |

### Debug options

| Option | Description |
|---|---|
| `--debug` | With `--rules`: instead of printing inferred axioms, print a body atom evaluation table showing each atom as PASS, FAIL, or SKIP with variable bindings, followed by the head (consequent) atoms and a satisfaction score. The table shows the single best-matching individual binding found by the path search — only one candidate row is reported per rule. With `--infer`: runs inference first, then prints the same evaluation table for every rule in the ontology. Output goes to stdout and can be redirected. |
| `--constraint <atom>` | With `--rules --debug`: seed a specific individual binding for evaluation. Format: `predicate(individual)` or `predicate(subject, object)` using prefix-qualified names (e.g. `obo:MyClass(recipe:r1.step1)`). Implies `--debug`. May be repeated to pin multiple atoms. When multiple `--rules` flags are used, constraints that don't match a given rule are silently skipped for that rule. With `--query` or `--query-text`: filter result rows to only those containing the specified individual IRI(s). |

### Examples

List all SWRL rules in an ontology:

    ./swrltabcli --list-rules ontology.ofn

List all SWRL rules with coloured variables (markdown `<pre>` block):

    ./swrltabcli --list-rules --format markdown ontology.ofn

List all SWRL rules as plain `.swrl` text, ready to paste into a `--file` input:

    ./swrltabcli --list-rules --format markdown --no-color ontology.ofn

Export all rules to a `.swrl` file, edit them, then reload and run:

    ./swrltabcli --list-rules --format txt ontology.ofn > my-rules.swrl
    # … edit my-rules.swrl in any text editor …
    ./swrltabcli --file my-rules.swrl --infer ontology.ofn

To persist the edits back into the ontology file:

    ./swrltabcli --file my-rules.swrl --save --list-rules ontology.ofn

Fire all rules and print inferred axioms:

    ./swrltabcli --infer ontology.ofn

Write inferred axioms to a separate OWL file (suitable as an `owl:imports` target):

    ./swrltabcli --infer --output-file inferred.ofn --output-iri http://example.org/inferred.ofn ontology.ofn

Fire a single named rule and capture inferences to a file:

    ./swrltabcli --rules "MyRuleName" --output-file inferred.ofn --output-iri http://example.org/inferred.ofn ontology.ofn

Fire a single named rule:

    ./swrltabcli --rules "MyRuleName" ontology.ofn

Fire multiple named rules in one inference pass:

    ./swrltabcli --rules "RuleA","RuleB" ontology.ofn

Rule and query names are stored as `rdfs:label` annotations in the ontology, so they can be plain words or natural-language phrases — e.g. `"combine two materials"` or `"find large inputs"` — making them easy to reference by meaning rather than by opaque identifiers. Names assigned automatically by Protégé are often less readable; you can rename them in Protégé or replace them via `--file --save`. Use `--list-rules` to see the exact names in your ontology.

Run an inline SWRL rule expression:

    ./swrltabcli --rule-text "MyClass(?x) -> MyOtherClass(?x)" ontology.ofn

Run an inline rule with debug output and a custom name:

    ./swrltabcli --rule-text "MyClass(?x) -> MyOtherClass(?x)" --rule-text-name "my-inline-rule" --debug --format markdown ontology.ofn

Debug a rule to see which body atoms are satisfied:

    ./swrltabcli --rules "MyRuleName" --debug ontology.ofn

Debug all rules in one pass (runs inference first, then evaluates each rule):

    ./swrltabcli --infer --debug --format markdown ontology.ofn > all-rules-report.md

Debug a rule with a specific individual bound to one of its body atoms:

    ./swrltabcli --rules "MyRuleName" --constraint "MyClass(prefix:myIndividual)" ontology.ofn

Debug a rule and save the evaluation report as a markdown file:

    ./swrltabcli --rules "MyRuleName" --debug --format markdown ontology.ofn > report.md

Run a named SQWRL query:

    ./swrltabcli --query "myQuery" ontology.ofn

Run an inline SQWRL query and get CSV output:

    ./swrltabcli --query-text "owl:Thing(?x) -> sqwrl:select(?x)" --format csv ontology.ofn

Run an inline SQWRL query with a result set name:

    ./swrltabcli --query-text "owl:Thing(?x) -> sqwrl:select(?x)" --query-name "all-things" ontology.ofn

Filter query results to rows containing a specific individual:

    ./swrltabcli --query "myQuery" --constraint "myProperty(prefix:myIndividual)" ontology.ofn

Load rules and queries from a file, then fire all rules:

    ./swrltabcli --file my-rules.swrl --infer ontology.ofn

Load rules from a file and run a named rule with debug output:

    ./swrltabcli --file my-rules.swrl --rules "MyRule" --debug --format markdown ontology.ofn

Load rules from a file and run a named query defined in that file:

    ./swrltabcli --file my-rules.swrl --query "MyQuery" ontology.ofn

Load updated rules from a file and save them back into the ontology (replaces any same-named rules):

    ./swrltabcli --file my-rules.swrl --save --list-rules ontology.ofn

Delete one or more named rules from the ontology and save:

    ./swrltabcli --rules "RuleA","RuleB" --delete --save ontology.ofn

### SWRL file format

When using `--file`, each entry in the file is declared with a `rule` or `query` keyword followed by its name, then the SWRL/SQWRL expression on the next line(s).  The `rule`/`query` keyword is for readability — SWRLAPI automatically distinguishes queries from rules by the presence of `sqwrl:` built-ins in the head.

```
# Whole-line comments begin with #
# Blank lines are ignored

rule TransitivePartOf
# A # comment immediately after the rule/query header (before the first body atom)
# is stored as the rule's rdfs:comment description in the ontology.
# Multiple consecutive comment lines are joined with a space.
part of(?x, ?y) ^ part of(?y, ?z) -> part of(?x, ?z)

rule "Rule With Spaces In Name"
MyClass(?x) ^ hasProperty(?x, ?y) -> MyOtherClass(?x)

# Multi-line rule — body lines are joined with a space before parsing.
# Leading indentation is ignored.  ^ and -> may appear at the start of
# a continuation line.  Inline comments are also supported.
rule ComplexProcessRule
# Fires when a process input material exceeds 10 kg.
  "has specified input"(?p, ?m)   # quoted labels are resolved via rdfs:label
  ^ "has characteristic"(?m, ?c)
  ^ "mass in kilograms"(?c, ?mass)
  ^ swrlb:greaterThan(?mass, 10)  # plain prefix:local names work directly
  -> "large input process"(?p)

query AllMaterials
  Material(?x) -> sqwrl:select(?x)
```

Key formatting rules:

- Rule/query names containing spaces must be enclosed in double quotes.
- Multi-line expressions are joined with a single space before parsing; leading indentation is stripped from each line.
- `^` and `->` may appear at the start of a continuation line.
- A `#` anywhere on a body line starts an inline comment (everything from `#` to end of line is discarded). Exception: `#` inside an angle-bracket IRI (`<http://example.org/ont#fragment>`) is preserved.
- **`# rule Name` / `# query Name`** — a whole-line comment whose text begins with `rule ` or `query ` (case-insensitive) is treated as a directive with the `#` stripped. This means both `rule MyRule` and `# rule MyRule` are valid directive forms.
- **Description comments** — one or more `#` comment lines placed immediately after a `rule`/`query` header line (before the first body atom) are stored as the rule's `rdfs:comment` annotation in the ontology. Multiple consecutive lines are joined with a space. These descriptions are round-tripped by `--list-rules --format txt` and `--format markdown` and are visible in Protégé's rule editor.
- **Quoted predicate labels** — a double- or single-quoted string used as a predicate (e.g. `'has specified input'(...)` or `"has specified input"(...)`) is automatically resolved to its ontology IRI via `rdfs:label` lookup across the full imports closure. The resolved IRI is expressed as a prefixed name if a matching prefix is declared, or as `<full-iri>` otherwise. An unresolved label produces a warning and will cause a parse error. This resolution also applies to `--rule-text` and `--query-text` inline expressions. The `--list-rules --format markdown` output uses single quotes.
- Unquoted predicate names must be valid SWRLAPI identifiers: an IRI fragment name (e.g. `hasSpecifiedInput`) or a prefixed name (e.g. `ex:hasSpecifiedInput`).

### Colour configuration

When `--format markdown` is active, argument values in the debug table are wrapped in HTML class spans (e.g. `<span class="material">...</span>`) and a `<style>` block is prepended.  The mapping from predicates to entity types, and from entity types to CSS rules, can be customised via a YAML config file.

Config is loaded from these locations in order (later entries override earlier ones):

1. `~/.swrltab/swrltab_config.yaml` — global user config
2. `swrltab_config.yaml` in the same directory as the ontology file — per-project config
3. The path given with `--config` — explicit override

Example `swrltab_config.yaml`:

```yaml
entity_styles:
  process: "span {color: blue;}"
  material: "span {color: green;}"
  characteristic: "span {color: tan;}"
  characteristic_value: "span {color: saddlebrown;}"
  information: "span {color: dimgray;}"
  error: "span {color: red;}"   # unbound variables — override to change highlight

predicate_styles:
  "has specified input":       [process, material]
  "has specified output":      [process, material]
  "has characteristic":        [material, characteristic]
  "has characteristic value":  [characteristic, characteristic_value]
  "has quantity":              [characteristic, material]
  "mass in kilograms":         [characteristic]
  "combining two materials":   [process]
  "add":                       [material]
  "temperature":               [characteristic]
  "characteristic value of":   [characteristic_value, characteristic]
```

Entity style values follow the pattern `"element {css-properties}"`, where `element` is the HTML element to use (e.g. `span`) and the braces contain standard CSS.  If a predicate has more arguments than entries in its style list, the last entry is repeated.

The built-in `error` entity style (red) is applied automatically to any argument that is still unbound at display time — identifiable by a leading `?` — in both the match and variables columns.  Override it in the config to change the unbound-variable highlight color.

### Import resolution

If a `catalog-v001.xml` file (as generated by Protégé) is present in the same directory as the ontology, the CLI applies it automatically to resolve `owl:imports` IRIs to local files. Only entries whose local file exists are used; no network fetches are attempted from the catalog.

### License

The software is licensed under the [BSD 2-clause License](https://github.com/protegeproject/swrltab/blob/master/license.txt).
