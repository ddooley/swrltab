# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Standalone SWRLTab is a Java Swing GUI application for editing and executing SWRL (Semantic Web Rule Language) rules and SQWRL queries against OWL ontologies. It is part of the Stanford Protégé project ecosystem. The application delegates rule execution to a Drools-based backend via the SWRLAPI framework.

## Build Commands

```bash
# Build with tests
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Package only (creates JARs in target/)
mvn clean package
```

## Headless CLI (`SWRLTabCLI`)

`SWRLTabCLI` runs SWRL inference or SQWRL queries without a display, writing results to stdout (progress/counts go to stderr).

```bash
# Via fat JAR (after mvn package)
java -cp target/swrltab-2.1.2-jar-with-dependencies.jar org.swrltab.ui.SWRLTabCLI [options] ontology.owl

# Via Maven (no pre-build needed)
mvn exec:java -Dexec.mainClass=org.swrltab.ui.SWRLTabCLI -Dexec.args="[options] ontology.owl"
```

**Modes (exactly one required):**

| Option | Description |
|--------|-------------|
| `--list-queries` | Print SQWRL queries stored in the ontology (name TAB expression) |
| `--list-rules` | Print SWRL rules (non-SQWRL), with active/inactive status |
| `--query <name>` | Run one named SQWRL query already in the ontology; prints column headers then rows |
| `--query-text <sqwrl>` | Run an inline SQWRL expression (use `--query-name` to name it; default: `cli-query`) |
| `--infer` | Fire all SWRL rules; output inferred axioms as **OWL Functional Syntax** to stdout |

**Output options:** `--format tsv|csv` (default: `tsv`) applies to query results.

**Notes:**
- SQWRL queries are selective — `--query <name>` runs only that one query.
- `--infer` fires *all* SWRL rules together (Drools does not support running a single rule in isolation). Prefix declarations are copied from the source ontology so IRIs appear in short prefixed form.
- Literal values print without XSD type suffixes; named entities print as their local (short) name.

## Running the GUI Applications

Two standalone Swing GUI apps are provided:

```bash
# SWRL rules editor (via Maven exec plugin)
mvn exec:java

# SWRL rules editor (via fat JAR)
java -cp ./target/swrltab-2.1.2-jar-with-dependencies.jar org.swrltab.ui.SWRLTab [path/to/ontology.owl]

# SQWRL queries editor (via fat JAR)
java -cp ./target/swrltab-2.1.2-jar-with-dependencies.jar org.swrltab.ui.SQWRLTab [path/to/ontology.owl]
```

Both applications accept an optional OWL ontology file path as an argument; without one they load a bundled sample ontology (`src/main/resources/projects/SWRLSimple.owl`).

## Running Tests

Tests live in the `my-drools-rules` submodule (not in the root module):

```bash
# Run all tests
mvn test

# Run tests in the submodule only
cd my-drools-rules && mvn test

# Run a single test class
mvn test -Dtest=recipe.RuleTest -pl my-drools-rules
```

## Architecture

### Module Layout

The root Maven module (`edu.stanford.swrl:swrltab`) is the main application. `my-drools-rules/` is an independent Maven module (separate `groupId`, not listed as a child in the root POM) that demonstrates standalone Drools rule authoring with the newer Drools 10 API.

### Main Entry Points

| Class | Description |
|-------|-------------|
| `org.swrltab.ui.SWRLTab` | SWRL rules editor — extends `JFrame`, implements `SWRLAPIView` |
| `org.swrltab.ui.SQWRLTab` | SQWRL queries editor — same structure, different view components |

### Component Relationships (MVC)

Both `SWRLTab` and `SQWRLTab` follow the same initialization pattern:

1. Load or create an OWL ontology via `OWLManager.createOWLOntologyManager()`
2. Create a `SWRLRuleEngine` (backed by Drools) via `SWRLAPIFactory`
3. Create a `FileBackedSWRLRuleEngineModel` / `FileBackedSQWRLQueryEngineModel` (Model)
4. Create a `SWRLRuleEngineDialogManager` and `SWRLRuleEngineMenuManager`
5. Instantiate the view (`SWRLRulesView` / `SQWRLQueriesView`) and wire everything together

The `SWRLAPIFactory` class (from the `swrlapi` dependency) is the central factory for all SWRLAPI objects. Views implement the `SWRLAPIView` interface and are refreshed via `update()`.

### Key Dependencies

- **swrlapi** — Core SWRL/SQWRL API and UI view components (`SWRLRulesView`, `SQWRLQueriesView`)
- **swrlapi-drools-engine** — Drools-based execution backend for the rule engine; brings in `drools-compiler`, `drools-mvel`, and `kie-internal` as transitive dependencies
- **owlapi-osgidistribution** — OWL ontology loading and manipulation
- **mvel2:2.5.2.Final** — Explicit override of the transitive `mvel2:2.4.x` pulled in by `drools-mvel`. MVEL 2.4.x references `java.lang.Compiler` which was removed in Java 17; 2.5.x fixes this while remaining API-compatible with Drools 7.74

## Java Version Compatibility

Requires **Java 17+** with the `mvel2:2.5.2.Final` override in pom.xml. Without it, MVEL's static initializer crashes with `NoClassDefFoundError: java/lang/Compiler` because `java.lang.Compiler` was removed in Java 17. The fix is already applied in pom.xml — do not remove or downgrade the explicit `mvel2` dependency.

### Logging

Log output goes to `~/workspace/log/swrltab.log` (rolling, max 100 MB). Configured in `src/main/resources/log4j.properties`.
