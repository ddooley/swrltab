package org.swrltab.ui;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.SimpleIRIMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import org.swrlapi.core.SWRLAPIRule;
import org.swrlapi.core.SWRLRuleEngine;
import org.swrlapi.core.SWRLRuleRenderer;
import org.swrlapi.exceptions.SWRLAPIException;
import org.swrlapi.exceptions.SWRLBuiltInException;
import org.swrlapi.factory.SWRLAPIFactory;
import org.swrlapi.parser.SWRLParseException;
import org.swrlapi.sqwrl.SQWRLQueryEngine;
import org.swrlapi.sqwrl.SQWRLResult;
import org.swrlapi.sqwrl.exceptions.SQWRLException;
import org.swrlapi.sqwrl.values.SQWRLResultValue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless command-line interface for running SWRL inference and SQWRL queries.
 *
 * <p>Usage via fat JAR:
 * <pre>
 *   java -cp target/swrltab-2.1.2-jar-with-dependencies.jar org.swrltab.ui.SWRLTabCLI [options] ontology.owl
 * </pre>
 *
 * <p>Usage via Maven:
 * <pre>
 *   mvn exec:java -Dexec.mainClass=org.swrltab.ui.SWRLTabCLI -Dexec.args="[options] ontology.owl"
 * </pre>
 */
public class SWRLTabCLI {

  public static void main(String[] args) {
    String ontologyPath = null;
    String queryName = null;
    String queryText = null;
    String queryTextName = "cli-query";
    List<String> ruleNames = new ArrayList<>();
    String ruleText = null;
    String ruleTextName = "cli-rule";
    List<String> ruleFiles = new ArrayList<>();
    boolean infer = false;
    boolean listQueries = false;
    boolean listRules = false;
    boolean ignoreImports = false;
    boolean save = false;
    boolean delete = false;
    boolean debug = false;
    boolean noColor = false;
    String configPath = null;
    String format = "tsv";
    String outputFilePath = null;
    String outputIri = null;
    List<String> constraints = new ArrayList<>();

    int i = 0;
    while (i < args.length) {
      switch (args[i]) {
        case "--query":
          if (++i >= args.length) usage("--query requires a name argument");
          queryName = args[i];
          break;
        case "--query-text":
          if (++i >= args.length) usage("--query-text requires a SQWRL expression");
          queryText = args[i];
          break;
        case "--query-name":
          if (++i >= args.length) usage("--query-name requires a name");
          queryTextName = args[i];
          break;
        case "--infer":
          infer = true;
          break;
        case "--list-queries":
          listQueries = true;
          break;
        case "--list-rules":
          listRules = true;
          break;
        case "--rules":
          if (++i >= args.length) usage("--rules requires a name argument");
          for (String n : args[i].split(",")) { String t = n.trim(); if (!t.isEmpty()) ruleNames.add(t); }
          break;
        case "--delete":
          delete = true;
          break;
        case "--rule-text":
          if (++i >= args.length) usage("--rule-text requires a SWRL expression");
          ruleText = args[i];
          break;
        case "--rule-text-name":
          if (++i >= args.length) usage("--rule-text-name requires a name");
          ruleTextName = args[i];
          break;
        case "--ignore-imports":
          ignoreImports = true;
          break;
        case "--debug":
          debug = true;
          break;
        case "--constraint":
          if (++i >= args.length) usage("--constraint requires an atom expression");
          constraints.add(args[i]);
          break;
        case "--format":
          if (++i >= args.length) usage("--format requires tsv, csv, markdown, or txt");
          format = args[i];
          if (!format.equals("tsv") && !format.equals("csv") && !format.equals("markdown")
              && !format.equals("txt"))
            usage("--format must be tsv, csv, markdown, or txt");
          break;
        case "--no-color":
          noColor = true;
          break;
        case "--file":
          if (++i >= args.length) usage("--file requires a file path");
          ruleFiles.add(args[i]);
          break;
        case "--save":
          save = true;
          break;
        case "--output-file":
          if (++i >= args.length) usage("--output-file requires a file path");
          outputFilePath = args[i];
          break;
        case "--output-iri":
          if (++i >= args.length) usage("--output-iri requires an IRI");
          outputIri = args[i];
          break;
        case "--config":
          if (++i >= args.length) usage("--config requires a file path");
          configPath = args[i];
          break;
        default:
          if (args[i].startsWith("--")) usage("Unknown option: " + args[i]);
          if (ontologyPath != null) usage("Multiple ontology paths given");
          ontologyPath = args[i];
          break;
      }
      i++;
    }

    int modes = (infer ? 1 : 0) + (queryName != null ? 1 : 0) + (queryText != null ? 1 : 0)
        + (listQueries ? 1 : 0) + (listRules ? 1 : 0)
        + (!ruleNames.isEmpty() && !delete ? 1 : 0)
        + (delete ? 1 : 0)
        + (ruleText != null ? 1 : 0);

    if (!constraints.isEmpty()) {
      boolean queryMode = (queryName != null || queryText != null);
      boolean ruleMode = (!ruleNames.isEmpty() && !delete) || ruleText != null;
      if (!ruleMode && !queryMode) {
        System.err.println("Warning: --constraint only applies to --rule, --rule-text, --query, or --query-text; ignoring.");
        constraints.clear();
      } else if (ruleMode) {
        debug = true; // --constraint implies --debug for rules
      }
    }

    if (ontologyPath == null) usage("An ontology file path is required");
    if (modes == 0)
      usage("Specify one of: --query, --query-text, --infer, --rules, --rule-text, --list-queries, --list-rules, --delete");
    if (modes > 1) usage("Only one mode may be used at a time");
    if (delete && ruleNames.isEmpty()) usage("--delete requires --rules <name[,name…]>");
    if (delete && !save) System.err.println("Warning: --delete without --save removes rules in memory only; changes will not be persisted");

    boolean inferenceMode = infer || (!ruleNames.isEmpty() && !delete) || ruleText != null;
    if (outputFilePath != null && !inferenceMode)
      System.err.println("Warning: --output-file only applies to --infer, --rules, or --rule-text; ignoring");
    if (outputIri != null && outputFilePath == null)
      System.err.println("Warning: --output-iri has no effect without --output-file");
    File outputFile = (outputFilePath != null && inferenceMode) ? new File(outputFilePath) : null;

    File owlFile = new File(ontologyPath);
    if (!owlFile.exists()) {
      System.err.println("Error: ontology file not found: " + owlFile.getAbsolutePath());
      System.exit(1);
    }
    SwrltabConfig config = loadConfig(owlFile.getParentFile(), configPath);

    try {
      OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

      // Auto-apply catalog-v001.xml from the ontology's directory when present.
      // This maps owl:imports IRIs to local files, mirroring Protégé behaviour.
      File catalogFile = new File(owlFile.getParentFile(), "catalog-v001.xml");
      String catalogNote = "";
      if (catalogFile.exists()) {
        int mapped = applyCatalog(manager, catalogFile);
        if (mapped > 0) catalogNote = mapped + " local import(s) from " + catalogFile.getName();
      }

      OWLOntology ontology;
      if (ignoreImports) {
        OWLOntologyLoaderConfiguration cfg = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
        ontology = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(owlFile), cfg);
      } else {
        ontology = manager.loadOntologyFromOntologyDocument(owlFile);
      }

      String ontologyName = owlFile.getName();
      Map<String, String> resolvedLabels = new LinkedHashMap<>(), resolvedBare = new LinkedHashMap<>();
      for (String fp : ruleFiles) loadSwrlFile(ontology, fp, resolvedLabels, resolvedBare);
      if (format.equals("markdown"))
        System.out.println("<style>\nbody, .markdown-body { max-width: 90%; }\n" + generateEntityCSS(config) + "\n</style>\n");
      if (infer) {
        runInference(manager, ontology, ontologyName, catalogNote, format, debug, noColor, config,
            outputFile, outputIri, resolvedLabels, resolvedBare);
      } else if (!ruleNames.isEmpty() && !delete) {
        runRules(manager, ontology, ruleNames, debug, constraints, format, ontologyName, catalogNote,
            noColor, config, outputFile, outputIri, resolvedLabels, resolvedBare);
      } else if (ruleText != null) {
        Map<String, IRI> labelToIRI = buildLabelToIRIMap(ontology);
        Map<String, String> ruleTextPrefixes = buildMergedPrefixMap(manager, ontology);
        ruleText = preprocessRuleText(ruleText, labelToIRI, ruleTextPrefixes, resolvedLabels, resolvedBare);
        printResolutionSummary(resolvedLabels, resolvedBare, ruleTextPrefixes);
        SWRLRuleEngine parseEngine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
        parseEngine.createSWRLRule(ruleTextName, ruleText);
        runRules(manager, ontology, Collections.singletonList(ruleTextName), debug, constraints,
            format, ontologyName, catalogNote, noColor, config, outputFile, outputIri,
            resolvedLabels, resolvedBare);
      } else if (delete) {
        int removed = 0;
        for (String name : ruleNames) {
          if (removeExistingRuleByName(ontology, name, false)) {
            removed++;
          } else {
            System.err.println("Warning: no rule/query named '" + name + "' found in ontology");
          }
        }
        System.err.println("Deleted " + removed + " of " + ruleNames.size() + " named rule(s)/query(ies)");
      } else if (listQueries) {
        System.err.println(ontologyHeader(ontologyName, catalogNote, format));
        listQueries(manager, ontology, format, noColor, config);
      } else if (listRules) {
        System.err.println(ontologyHeader(ontologyName, catalogNote, format));
        listRules(manager, ontology, format, noColor, config);
      } else {
        String name = (queryText != null) ? queryTextName : queryName;
        runQuery(ontology, name, queryText, format, debug, constraints, ontologyName, catalogNote);
      }
      if (save) {
        if (ruleFiles.isEmpty() && !delete) {
          System.err.println("Warning: --save has no effect without --file or --delete");
        } else {
          OWLDocumentFormat fmt = manager.getOntologyFormat(ontology);
          try (OutputStream os = Files.newOutputStream(owlFile.toPath())) {
            manager.saveOntology(ontology, fmt, os);
          }
          System.err.println("Saved updated ontology to " + owlFile.getName());
        }
      }
    } catch (IOException e) {
      System.err.println("I/O error: " + e.getMessage());
      System.exit(1);
    } catch (OWLOntologyCreationException e) {
      System.err.println("Error loading ontology: " + e.getMessage());
      System.exit(1);
    } catch (OWLOntologyStorageException e) {
      System.err.println("Error serialising axioms: " + e.getMessage());
      System.exit(1);
    } catch (SWRLParseException e) {
      System.err.println("SWRL parse error: " + e.getMessage());
      System.exit(1);
    } catch (SQWRLException e) {
      System.err.println("SQWRL error: " + e.getMessage());
      System.exit(1);
    } catch (SWRLBuiltInException e) {
      System.err.println("SWRL built-in error: " + e.getMessage());
      System.exit(1);
    } catch (SWRLAPIException e) {
      System.err.println("SWRLAPI error: " + e.getMessage());
      System.exit(1);
    }
  }

  // ---------------------------------------------------------------------------
  // File loading
  // ---------------------------------------------------------------------------

  /**
   * Removes any existing {@code SWRLRule} axiom in the ontology whose {@code rdfs:label}
   * annotation matches {@code name}.  SWRLAPI stores rule and query names as {@code rdfs:label}
   * annotations; calling this before {@link SWRLRuleEngine#createSWRLRule} ensures that loading
   * a rule from a file replaces, rather than duplicates, any same-named rule already in the
   * ontology.
   */
  private static boolean removeExistingRuleByName(OWLOntology ontology, String name,
      boolean replacing) {
    OWLOntologyManager manager = ontology.getOWLOntologyManager();
    OWLAnnotationProperty labelProp = manager.getOWLDataFactory().getRDFSLabel();
    for (SWRLRule r : new ArrayList<>(ontology.getAxioms(AxiomType.SWRL_RULE))) {
      for (OWLAnnotation ann : r.getAnnotations(labelProp)) {
        if (ann.getValue() instanceof OWLLiteral
            && name.equals(((OWLLiteral) ann.getValue()).getLiteral())) {
          manager.removeAxiom(ontology, r);
          System.err.println("  " + (replacing ? "Replacing" : "Removing") + " rule/query: " + name);
          return true;
        }
      }
    }
    return false;
  }

  /** Returns the first {@code rdfs:comment} literal on a rule axiom, or {@code null} if absent. */
  private static String ruleComment(SWRLRule rule, OWLAnnotationProperty commentProp) {
    for (OWLAnnotation ann : rule.getAnnotations(commentProp)) {
      if (ann.getValue() instanceof OWLLiteral) {
        String text = ((OWLLiteral) ann.getValue()).getLiteral();
        if (!text.isEmpty()) return text;
      }
    }
    return null;
  }

  /**
   * Adds (or replaces) an {@code rdfs:comment} annotation on the {@code SWRLRule} axiom whose
   * {@code rdfs:label} matches {@code name}.  Called after
   * {@link SWRLRuleEngine#createSWRLRule} to attach a description loaded from a {@code .swrl}
   * file.
   */
  private static void addCommentToRule(OWLOntology ontology, String name, String comment) {
    OWLOntologyManager manager = ontology.getOWLOntologyManager();
    OWLDataFactory df = manager.getOWLDataFactory();
    OWLAnnotationProperty labelProp = df.getRDFSLabel();
    OWLAnnotationProperty commentProp = df.getRDFSComment();
    for (SWRLRule r : new ArrayList<>(ontology.getAxioms(AxiomType.SWRL_RULE))) {
      for (OWLAnnotation ann : r.getAnnotations(labelProp)) {
        if (ann.getValue() instanceof OWLLiteral
            && name.equals(((OWLLiteral) ann.getValue()).getLiteral())) {
          // Collect existing annotations (keeps rdfs:label etc.) minus any prior rdfs:comment,
          // then add the new one.
          Set<OWLAnnotation> newAnns = new HashSet<>(r.getAnnotations());
          newAnns.removeIf(a -> a.getProperty().equals(commentProp));
          newAnns.add(df.getOWLAnnotation(commentProp, df.getOWLLiteral(comment)));
          manager.removeAxiom(ontology, r);
          manager.addAxiom(ontology, r.getAnnotatedAxiom(newAnns));
          return;
        }
      }
    }
  }

  /**
   * Parses a SWRL/SQWRL text file and registers each entry in the ontology.
   *
   * <p>File format:
   * <pre>
   *   # comment
   *
   *   rule MyRuleName
   *   Body1(?x) ^ Body2(?x, ?y) -> Head(?x)
   *
   *   query MyQueryName
   *   Body(?x) -> sqwrl:select(?x)
   * </pre>
   *
   * <p>The {@code rule} / {@code query} keyword is for readability only — SWRLAPI
   * automatically detects queries by the presence of {@code sqwrl:} built-ins in the head.
   * Rule and query names may optionally be enclosed in double quotes.
   * Body lines following a directive are joined with a single space before parsing,
   * so multi-line expressions are supported.
   */
  private static void loadSwrlFile(OWLOntology ontology, String filePath,
      Map<String, String> resolvedLabels, Map<String, String> resolvedBarePredicates)
      throws IOException, SWRLParseException, SWRLBuiltInException {
    List<String> lines = Files.readAllLines(new File(filePath).toPath());
    SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

    // Build label-resolution tables once for the whole file.
    Map<String, IRI> labelToIRI = buildLabelToIRIMap(ontology);
    Map<String, String> prefixes = buildMergedPrefixMap(ontology.getOWLOntologyManager(), ontology);

    // Snapshot existing keys so we can report only new resolutions for this file.
    Set<String> prevLabels = new HashSet<>(resolvedLabels.keySet());
    Set<String> prevBare   = new HashSet<>(resolvedBarePredicates.keySet());

    String currentName = null;
    String currentComment = null;
    boolean awaitingBody = false;
    StringBuilder body = new StringBuilder();
    int loaded = 0;

    for (String rawLine : lines) {
      String trimmed = rawLine.trim();
      String line;
      // Accept "# rule Name" / "# query Name" as directives so that output from
      // --list-rules / --list-queries can be pasted directly back into a .swrl file.
      if (trimmed.startsWith("#")) {
        String afterHash = trimmed.substring(1).trim();
        String afterLower = afterHash.toLowerCase();
        if (afterLower.startsWith("rule ") || afterLower.startsWith("query ")) {
          line = stripInlineComment(afterHash).trim(); // drop '#' and any trailing inline comment
        } else {
          // A # comment line that appears after a rule/query header but before the first body
          // atom is treated as the rule's rdfs:comment description.  Multiple consecutive
          // comment lines are joined with a space.
          if (awaitingBody && currentName != null && !afterHash.isEmpty()) {
            currentComment = (currentComment == null) ? afterHash : currentComment + " " + afterHash;
          }
          continue; // not a body line
        }
      } else {
        line = stripInlineComment(rawLine).trim();
        if (line.isEmpty()) continue;
      }

      String lower = line.toLowerCase();
      if (lower.startsWith("rule ") || lower.startsWith("query ")) {
        // Flush previous entry before starting a new one
        if (currentName != null && body.length() > 0) {
          String expr = preprocessRuleText(body.toString().trim(), labelToIRI, prefixes, resolvedLabels, resolvedBarePredicates);
          removeExistingRuleByName(ontology, currentName, true);
          engine.createSWRLRule(currentName, expr);
          if (currentComment != null) addCommentToRule(ontology, currentName, currentComment);
          loaded++;
          body.setLength(0);
        }
        int space = line.indexOf(' ');
        currentName = line.substring(space + 1).trim();
        // Strip optional enclosing quotes (double or single)
        if (currentName.length() > 2
            && ((currentName.startsWith("\"") && currentName.endsWith("\""))
                || (currentName.startsWith("'") && currentName.endsWith("'"))))
          currentName = currentName.substring(1, currentName.length() - 1);
        currentComment = null;
        awaitingBody = true;
      } else if (currentName != null) {
        // Continuation of the current rule/query body
        if (body.length() > 0) body.append(" ");
        body.append(line);
        awaitingBody = false;
      } else {
        System.err.println("Warning: skipping line before any rule/query declaration in "
            + filePath + ": " + line);
      }
    }

    // Flush the last entry
    if (currentName != null && body.length() > 0) {
      String expr = preprocessRuleText(body.toString().trim(), labelToIRI, prefixes, resolvedLabels, resolvedBarePredicates);
      removeExistingRuleByName(ontology, currentName, true);
      engine.createSWRLRule(currentName, expr);
      if (currentComment != null) addCommentToRule(ontology, currentName, currentComment);
      loaded++;
    }

    System.err.println("Loaded " + loaded + " rule(s)/query(ies) from " + filePath);
    Map<String, String> newLabels = new LinkedHashMap<>(), newBare = new LinkedHashMap<>();
    resolvedLabels.forEach((k, v) -> { if (!prevLabels.contains(k)) newLabels.put(k, v); });
    resolvedBarePredicates.forEach((k, v) -> { if (!prevBare.contains(k)) newBare.put(k, v); });
    printResolutionSummary(newLabels, newBare, prefixes);
  }

  /**
   * Strips an inline comment from a rule body line.  A {@code #} character that
   * is <em>not</em> inside an angle-bracket IRI ({@code <http://...#fragment>})
   * is treated as the start of a comment; everything from that character to the
   * end of the line is discarded.  Leading/trailing whitespace is also removed.
   */
  private static String stripInlineComment(String rawLine) {
    boolean inAngleBracket = false;
    for (int i = 0; i < rawLine.length(); i++) {
      char c = rawLine.charAt(i);
      if      (c == '<') inAngleBracket = true;
      else if (c == '>') inAngleBracket = false;
      else if (c == '#' && !inAngleBracket)
        return rawLine.substring(0, i).stripTrailing();
    }
    return rawLine;
  }

  /**
   * Inverts the {@code IRI → label} map returned by {@link #buildLabelMap} to
   * produce a {@code label → IRI} lookup.  Warns on duplicate labels.
   */
  private static Map<String, IRI> buildLabelToIRIMap(OWLOntology ontology) {
    Map<String, IRI> inverse = new HashMap<>();
    for (Map.Entry<IRI, String> e : buildLabelMap(ontology).entrySet()) {
      IRI prev = inverse.put(e.getValue(), e.getKey());
      if (prev != null && !prev.equals(e.getKey()))
        System.err.println("Warning: duplicate rdfs:label \"" + e.getValue()
            + "\" on <" + prev + "> and <" + e.getKey() + "> — using latter");
    }
    return inverse;
  }

  /**
   * Converts an IRI to the shortest available prefixed form (e.g. {@code ex:Foo})
   * using the supplied prefix-name → namespace map, or falls back to
   * {@code <full-iri>} if no prefix covers it.
   */
  private static String iriToPrefixed(IRI iri, Map<String, String> prefixes) {
    String iriStr = iri.toString();
    String bestPrefix = null, bestNs = null;
    for (Map.Entry<String, String> e : prefixes.entrySet()) {
      String ns = e.getValue();
      if (iriStr.startsWith(ns) && (bestNs == null || ns.length() > bestNs.length())) {
        bestNs = ns;
        bestPrefix = e.getKey(); // OWLAPI keys include trailing colon, e.g. "ex:"
      }
    }
    if (bestPrefix != null) {
      String local = iriStr.substring(bestNs.length());
      if (!local.isEmpty()) return bestPrefix + local;
    }
    return "<" + iriStr + ">";
  }

  /**
   * Pre-processes SWRL/SQWRL text by resolving {@code "quoted label"} tokens
   * used as predicates (i.e. immediately followed by {@code (}) to their
   * ontology IRIs.  Labels are matched against {@code rdfs:label} annotations
   * across the full imports closure (English/untagged preferred).
   *
   * <p>Example: {@code "has specified input"(?p, ?m)} becomes
   * {@code ex:hasSpecifiedInput(?p, ?m)} (or {@code <http://...#hasSpecifiedInput>(?p, ?m)}
   * if no prefix covers the IRI).
   *
   * <p>Unresolved labels produce a stderr warning and are left unchanged, which
   * will cause a SWRL parse error at rule creation time.
   */
  /**
   * Resolves quoted labels and bare predicate tokens in a SWRL/SQWRL expression.
   * Resolutions are accumulated in {@code resolvedLabels} and {@code resolvedBarePredicates}
   * (keyed by original name, value is the resolved prefixed IRI).  Already-cached entries are
   * reused directly, avoiding redundant {@code iriToPrefixed} calls across multiple rules.
   * Callers print the summary with {@link #printResolutionSummary} after all rules are processed.
   */
  private static String preprocessRuleText(String text, Map<String, IRI> labelToIRI,
      Map<String, String> prefixes, Map<String, String> resolvedLabels,
      Map<String, String> resolvedBarePredicates) {
    // First pass: resolve "double-quoted label"( and 'single-quoted label'( tokens.
    if (text.contains("\"") || text.contains("'")) {
      Pattern quoted = Pattern.compile("(?:\"([^\"]+)\"|'([^']+)')(?=\\s*\\()");
      Matcher m = quoted.matcher(text);
      StringBuffer sb = new StringBuffer();
      while (m.find()) {
        String label = m.group(1) != null ? m.group(1) : m.group(2);
        String replacement;
        if (resolvedLabels.containsKey(label)) {
          replacement = Matcher.quoteReplacement(iriToPrefixed(IRI.create(resolvedLabels.get(label)), prefixes));
        } else {
          IRI iri = labelToIRI.get(label);
          if (iri == null) {
            System.err.println("Warning: no ontology entity with rdfs:label \""
                + label + "\" — leaving unchanged (likely causes a parse error)");
            replacement = Matcher.quoteReplacement(m.group(0));
          } else {
            resolvedLabels.put(label, iri.toString());
            replacement = Matcher.quoteReplacement(iriToPrefixed(iri, prefixes));
          }
        }
        m.appendReplacement(sb, replacement);
      }
      m.appendTail(sb);
      text = sb.toString();
    }

    // Second pass: resolve bare (unquoted, unprefixed) predicate tokens via rdfs:label.
    // SWRLAPI resolves unquoted names by IRI fragment; if the property's rdfs:label differs
    // from its IRI fragment, SWRLAPI will fail.  This pass catches those cases by performing
    // a label lookup for every bare identifier immediately before '('.
    // Tokens already covered by a namespace prefix (e.g. swrlb:greaterThan, owl:Thing)
    // have a ':' before the local part — excluded by the negative lookbehind — and are
    // left for SWRLAPI to resolve directly.
    if (!labelToIRI.isEmpty()) {
      // Matches a plain identifier (no preceding ':', word-char, quote, or '<')
      // immediately followed by optional whitespace then '('
      Pattern bareToken = Pattern.compile("(?<![:\\w'\"<])([A-Za-z_][A-Za-z0-9_]*)(?=\\s*\\()");
      Matcher m2 = bareToken.matcher(text);
      StringBuffer sb2 = new StringBuffer();
      boolean anyResolved = false;
      while (m2.find()) {
        String token = m2.group(1);
        if (resolvedBarePredicates.containsKey(token)) {
          m2.appendReplacement(sb2, Matcher.quoteReplacement(iriToPrefixed(IRI.create(resolvedBarePredicates.get(token)), prefixes)));
          anyResolved = true;
        } else {
          IRI iri = labelToIRI.get(token);
          if (iri != null) {
            resolvedBarePredicates.put(token, iri.toString());
            m2.appendReplacement(sb2, Matcher.quoteReplacement(iriToPrefixed(iri, prefixes)));
            anyResolved = true;
          } else {
            m2.appendReplacement(sb2, Matcher.quoteReplacement(token));
          }
        }
      }
      m2.appendTail(sb2);
      if (anyResolved) text = sb2.toString();
    }

    return text;
  }

  /** Prints a summary of label and bare-predicate resolutions accumulated across all rules. */
  private static void printResolutionSummary(Map<String, String> resolvedLabels,
      Map<String, String> resolvedBarePredicates, Map<String, String> prefixes) {
    if (!resolvedLabels.isEmpty()) {
      System.err.println("  Quoted labels resolved:");
      resolvedLabels.forEach((k, v) -> System.err.println("    \"" + k + "\" → " + iriToPrefixed(IRI.create(v), prefixes)));
    }
    if (!resolvedBarePredicates.isEmpty()) {
      System.err.println("  Bare predicates resolved:");
      resolvedBarePredicates.forEach((k, v) -> System.err.println("    " + k + " → " + iriToPrefixed(IRI.create(v), prefixes)));
    }
  }

  // ---------------------------------------------------------------------------
  // Modes
  // ---------------------------------------------------------------------------

  /**
   * Fires all SWRL rules in the ontology and prints inferred axioms to stdout
   * in OWL Functional Syntax format. Prefix declarations are copied from the
   * source ontology so that IRIs appear in prefixed (short) form where possible.
   */
  private static void runInference(OWLOntologyManager manager, OWLOntology ontology,
      String ontologyName, String catalogNote, String format, boolean debug, boolean noColor,
      SwrltabConfig config, File outputFile, String outputIri,
      Map<String, String> resolvedLabels, Map<String, String> resolvedBare)
      throws SWRLAPIException, OWLOntologyCreationException, OWLOntologyStorageException,
             IOException {
    SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);

    // Collect user-defined rules BEFORE inference: calling infer() causes the engine to
    // generate additional rules derived from OWL axioms (class membership, individuals,
    // etc.) which would otherwise appear in getSWRLRules() alongside user rules.
    List<SWRLAPIRule> userRules = null;
    if (debug) {
      userRules = new ArrayList<>(engine.getSWRLRules());
      userRules.removeIf(SWRLAPIRule::isSQWRLQuery);
      userRules.sort(Comparator.comparing(SWRLAPIRule::getRuleName, String.CASE_INSENSITIVE_ORDER));
    }

    engine.infer();
    Set<OWLAxiom> inferred = engine.getInferredOWLAxioms();

    if (debug) {
      // Debug mode: print a body-atom evaluation table for every SWRL rule.
      System.out.println(ontologyHeader(ontologyName, catalogNote, inferred.size(), format));
      System.out.println(debugLegend(format, config, resolvedLabels, resolvedBare));
      OWLDataFactory df = manager.getOWLDataFactory();
      Map<IRI, String> labels = buildLabelMap(ontology);
      Map<IRI, Set<IRI>> inverseMap = buildInversePropertyMap(ontology);
      List<SWRLAPIRule> rules = userRules;
      boolean first = true;
      for (SWRLAPIRule rule : rules) {
        if (!first) {
          System.out.println(format.equals("markdown") ? "\n---\n" : "");
        }
        first = false;
        List<SWRLAtom> bodyAtoms = new ArrayList<>(rule.getBody());
        PathResult best = searchPaths(bodyAtoms, 0,
            new LinkedHashMap<>(), new LinkedHashMap<>(), ontology, df);
        evaluateRuleBody(rule, ontology, best.indBindings, labels,
            Collections.emptySet(), inverseMap, format, noColor, config);
        reportUndeclaredTerms(rule, ontology);
      }
      return;
    }

    System.err.println(ontologyHeader(ontologyName, catalogNote, inferred.size(), format));
    if (inferred.isEmpty() && outputFile == null) return;

    OWLOntology output = (outputIri != null)
        ? manager.createOntology(IRI.create(outputIri))
        : manager.createOntology();
    manager.addAxioms(output, inferred);

    // Carry prefix declarations from the source ontology for readable IRI output.
    FunctionalSyntaxDocumentFormat outputFmt = new FunctionalSyntaxDocumentFormat();
    OWLDocumentFormat srcFormat = manager.getOntologyFormat(ontology);
    if (srcFormat != null && srcFormat.isPrefixOWLOntologyFormat()) {
      outputFmt.copyPrefixesFrom(srcFormat.asPrefixOWLOntologyFormat());
    }

    if (outputFile != null) {
      try (OutputStream os = Files.newOutputStream(outputFile.toPath())) {
        manager.saveOntology(output, outputFmt, os);
      }
      System.err.println("Wrote " + inferred.size() + " inferred axiom(s) to " + outputFile.getPath());
    } else {
      manager.saveOntology(output, outputFmt, System.out);
    }
  }

  /**
   * Fires one or more named SWRL rules by removing all other plain SWRL rules
   * from the ontology before running inference.  Rules are run together in a
   * single inference pass and displayed in the order they were specified.
   * Inferred axioms are printed to stdout in OWL Functional Syntax format;
   * SQWRL queries are left untouched.
   */
  private static void runRules(OWLOntologyManager manager, OWLOntology ontology,
      List<String> targetRules, boolean debug, List<String> constraints, String format,
      String ontologyName, String catalogNote, boolean noColor, SwrltabConfig config,
      File outputFile, String outputIri,
      Map<String, String> resolvedLabels, Map<String, String> resolvedBare)
      throws SWRLAPIException, OWLOntologyCreationException, OWLOntologyStorageException,
             IOException {
    // Resolve rule names via SWRLAPI's naming logic.  Build an ordered list of
    // target rule objects and a list of all non-target rules to be removed.
    SWRLRuleEngine tempEngine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
    Map<String, SWRLAPIRule> targetRuleMap = new LinkedHashMap<>();
    Set<String> queryNames = new HashSet<>();
    List<SWRLAPIRule> toRemove = new ArrayList<>();
    for (SWRLAPIRule r : tempEngine.getSWRLRules()) {
      if (r.isSQWRLQuery()) {
        if (targetRules.contains(r.getRuleName())) queryNames.add(r.getRuleName());
        continue;
      }
      if (targetRules.contains(r.getRuleName())) targetRuleMap.put(r.getRuleName(), r);
      else toRemove.add(r);
    }
    for (String name : targetRules) {
      if (!targetRuleMap.containsKey(name)) {
        System.err.println("Error: no SWRL rule named '" + name + "'");
        if (queryNames.contains(name))
          System.err.println("'" + name + "' is a SQWRL query — use --query instead of --rule.");
        else
          System.err.println("Use --list-rules to see available rule names. Perhaps this is a query, not a rule?");
        System.exit(1);
      }
    }
    // Preserve the user-specified order.
    List<SWRLAPIRule> orderedRules = new ArrayList<>();
    for (String name : targetRules) orderedRules.add(targetRuleMap.get(name));

    // Remove only the non-target rules so only the requested rules fire.
    for (SWRLAPIRule r : toRemove) manager.removeAxiom(ontology, r);

    // Single inference pass over all target rules together.
    SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
    engine.infer();
    Set<OWLAxiom> inferred = engine.getInferredOWLAxioms();

    if (debug) {
      System.out.println(ontologyHeader(ontologyName, catalogNote, inferred.size(), format));
      System.out.println(debugLegend(format, config, resolvedLabels, resolvedBare));
      boolean md = format.equals("markdown");
      OWLDataFactory df = manager.getOWLDataFactory();
      Map<String, String> mergedPrefixes = buildMergedPrefixMap(manager, ontology);
      Map<IRI, String> labels = buildLabelMap(ontology);
      Map<IRI, Set<IRI>> inverseMap = buildInversePropertyMap(ontology);
      // When constraints are given with multiple rules, apply them only to rules
      // whose body contains a matching atom (silently skip non-matching rules).
      boolean warnMissing = (orderedRules.size() == 1);
      boolean first = true;
      for (SWRLAPIRule rule : orderedRules) {
        if (!first) System.out.println(md ? "\n---\n" : "");
        first = false;
        Set<SWRLAtom> matchedAtoms = new HashSet<>();
        Map<IRI, IRI> constraintBindings = constraints.isEmpty()
            ? new LinkedHashMap<>()
            : resolveConstraintBindings(constraints, rule, mergedPrefixes, labels, matchedAtoms, warnMissing);
        List<SWRLAtom> bodyAtoms = new ArrayList<>(rule.getBody());
        PathResult best = searchPaths(bodyAtoms, 0, constraintBindings, new LinkedHashMap<>(), ontology, df);
        evaluateRuleBody(rule, ontology, best.indBindings, labels, matchedAtoms, inverseMap, format, noColor, config);
        reportUndeclaredTerms(rule, ontology);
      }
      return;
    }

    System.err.println(ontologyHeader(ontologyName, catalogNote, inferred.size(), format));
    if (inferred.isEmpty() && outputFile == null) return;

    OWLOntology output = (outputIri != null)
        ? manager.createOntology(IRI.create(outputIri))
        : manager.createOntology();
    manager.addAxioms(output, inferred);

    FunctionalSyntaxDocumentFormat outputFmt = new FunctionalSyntaxDocumentFormat();
    OWLDocumentFormat srcFormat = manager.getOntologyFormat(ontology);
    if (srcFormat != null && srcFormat.isPrefixOWLOntologyFormat()) {
      outputFmt.copyPrefixesFrom(srcFormat.asPrefixOWLOntologyFormat());
    }

    if (outputFile != null) {
      try (OutputStream os = Files.newOutputStream(outputFile.toPath())) {
        manager.saveOntology(output, outputFmt, os);
      }
      System.err.println("Wrote " + inferred.size() + " inferred axiom(s) to " + outputFile.getPath());
    } else {
      manager.saveOntology(output, outputFmt, System.out);
    }
  }

  /**
   * Runs a single named SQWRL query (already stored in the ontology) or an
   * inline SQWRL expression and prints tabular results to stdout.
   * Column headers are printed first; progress is reported on stderr.
   *
   * @param queryText  null to run a named query already in the ontology;
   *                   non-null to run this inline expression
   */
  private static void runQuery(OWLOntology ontology, String name, String queryText,
      String format, boolean debug, List<String> constraints,
      String ontologyName, String catalogNote)
      throws SWRLAPIException, SWRLParseException, SQWRLException {
    System.err.println(ontologyHeader(ontologyName, catalogNote, format));

    // Build IRI filter from constraint arguments (predicate part is ignored for queries)
    Set<IRI> filterIRIs = new HashSet<>();
    if (!constraints.isEmpty()) {
      Map<String, String> mergedPrefixes =
          buildMergedPrefixMap(ontology.getOWLOntologyManager(), ontology);
      for (String raw : constraints) {
        int parenOpen  = raw.indexOf('(');
        int parenClose = raw.lastIndexOf(')');
        if (parenOpen >= 0 && parenClose > parenOpen) {
          for (String tok : raw.substring(parenOpen + 1, parenClose).split(",", -1)) {
            IRI iri = resolveIRI(tok.trim(), mergedPrefixes);
            if (iri != null) filterIRIs.add(iri);
          }
        }
      }
    }

    if (queryText != null) {
      Map<String, String> prefixes = buildMergedPrefixMap(ontology.getOWLOntologyManager(), ontology);
      Map<String, String> qtLabels = new LinkedHashMap<>(), qtBare = new LinkedHashMap<>();
      queryText = preprocessRuleText(queryText, buildLabelToIRIMap(ontology), prefixes, qtLabels, qtBare);
      printResolutionSummary(qtLabels, qtBare, prefixes);
    }
    SQWRLQueryEngine engine = SWRLAPIFactory.createSQWRLQueryEngine(ontology);
    SQWRLResult result = (queryText != null)
        ? engine.runSQWRLQuery(name, queryText)
        : engine.runSQWRLQuery(name);

    List<String> columns = result.getColumnNames();
    int rows = 0;

    if (format.equals("markdown")) {
      System.out.println(mdRow(columns));
      System.out.println(mdSep(columns.size()));
      while (result.next()) {
        List<SQWRLResultValue> row = result.getRow();
        if (!filterIRIs.isEmpty() && !rowMatchesFilter(row, filterIRIs)) continue;
        List<String> cells = new ArrayList<>(row.size());
        for (SQWRLResultValue v : row) cells.add(renderValue(v));
        System.out.println(mdRow(cells));
        rows++;
      }
    } else {
      char sep = format.equals("csv") ? ',' : '\t';
      System.out.println(joinRow(columns, sep));
      while (result.next()) {
        List<SQWRLResultValue> row = result.getRow();
        if (!filterIRIs.isEmpty() && !rowMatchesFilter(row, filterIRIs)) continue;
        String[] cells = new String[row.size()];
        for (int j = 0; j < row.size(); j++) cells[j] = renderValue(row.get(j));
        System.out.println(joinRow(Arrays.asList(cells), sep));
        rows++;
      }
    }
    System.err.println("# " + rows + " row(s)");

    if (debug) {
      SWRLAPIRule queryRule = null;
      for (SWRLAPIRule r : engine.getSWRLRules()) {
        if (r.getRuleName().equals(name)) { queryRule = r; break; }
      }
      if (queryRule != null) reportUndeclaredTerms(queryRule, ontology);
    }
  }

  /** Returns true if any entity value in the row has an IRI contained in {@code filterIRIs}. */
  private static boolean rowMatchesFilter(List<SQWRLResultValue> row, Set<IRI> filterIRIs)
      throws SQWRLException {
    for (SQWRLResultValue v : row) {
      if (v.isEntity() && filterIRIs.contains(v.asEntityResult().getIRI())) return true;
    }
    return false;
  }

  /**
   * Lists all SQWRL queries stored in the ontology.
   * Plain format: name TAB expression.
   * Markdown format: {@code <pre>} block in .swrl file syntax with optionally coloured variables.
   * With {@code --no-color} the output is spans-free and can be pasted directly into a --file input.
   */
  private static void listQueries(OWLOntologyManager manager, OWLOntology ontology,
      String format, boolean noColor, SwrltabConfig config) throws SWRLAPIException {
    SQWRLQueryEngine engine = SWRLAPIFactory.createSQWRLQueryEngine(ontology);
    SWRLRuleRenderer renderer = engine.createSWRLRuleRenderer();
    List<SWRLAPIRule> queries = new ArrayList<>(engine.getSWRLRules());
    queries.removeIf(r -> !r.isSQWRLQuery());
    queries.sort(Comparator.comparing(SWRLAPIRule::getRuleName, String.CASE_INSENSITIVE_ORDER));
    if (format.equals("markdown") || format.equals("txt")) {
      Map<IRI, String> labels = buildLabelMap(ontology);
      Map<String, String> prefixes = buildMergedPrefixMap(manager, ontology);
      OWLAnnotationProperty commentProp = manager.getOWLDataFactory().getRDFSComment();
      boolean pre = format.equals("markdown");
      boolean effectiveNoColor = format.equals("txt") || noColor;
      if (pre) System.out.println("<pre>");
      boolean first = true;
      for (SWRLAPIRule r : queries) {
        if (!first) System.out.println();
        first = false;
        String comment = ruleComment(r, commentProp);
        System.out.println("query " + r.getRuleName());
        printAtomBlock(r, labels, prefixes, effectiveNoColor, config, comment);
      }
      if (pre) System.out.println("</pre>");
    } else {
      for (SWRLAPIRule r : queries) {
        System.out.println(r.getRuleName() + "\t" + renderer.renderSWRLRule(r));
      }
    }
  }

  /**
   * Lists all plain SWRL rules (non-SQWRL) stored in the ontology.
   * Plain format: [active/inactive] expression.
   * Markdown format: {@code <pre>} block in .swrl file syntax with optionally coloured variables.
   * With {@code --no-color} the output is spans-free and can be pasted directly into a --file input.
   */
  private static void listRules(OWLOntologyManager manager, OWLOntology ontology,
      String format, boolean noColor, SwrltabConfig config) throws SWRLAPIException {
    SWRLRuleEngine engine = SWRLAPIFactory.createSWRLRuleEngine(ontology);
    SWRLRuleRenderer renderer = engine.createSWRLRuleRenderer();
    List<SWRLAPIRule> rules = new ArrayList<>(engine.getSWRLRules());
    rules.removeIf(SWRLAPIRule::isSQWRLQuery);
    rules.sort(Comparator.comparing(SWRLAPIRule::getRuleName, String.CASE_INSENSITIVE_ORDER));
    if (format.equals("markdown") || format.equals("txt")) {
      Map<IRI, String> labels = buildLabelMap(ontology);
      Map<String, String> prefixes = buildMergedPrefixMap(manager, ontology);
      OWLAnnotationProperty commentProp = manager.getOWLDataFactory().getRDFSComment();
      boolean pre = format.equals("markdown");
      boolean effectiveNoColor = format.equals("txt") || noColor;
      if (pre) System.out.println("<pre>");
      boolean first = true;
      for (SWRLAPIRule r : rules) {
        if (!first) System.out.println();
        first = false;
        String status = r.isActive() ? "" : "  # inactive";
        String comment = ruleComment(r, commentProp);
        System.out.println("rule " + r.getRuleName() + status);
        printAtomBlock(r, labels, prefixes, effectiveNoColor, config, comment);
      }
      if (pre) System.out.println("</pre>");
    } else {
      for (SWRLAPIRule r : rules) {
        System.out.printf("[%s] %s%n",
            r.isActive() ? "active" : "inactive", renderer.renderSWRLRule(r));
      }
    }
  }

  /**
   * Emits the body and head atoms of a rule as plain-text lines in .swrl file syntax:
   * the first body atom has no prefix; subsequent body atoms are prefixed with {@code ^};
   * head atoms are prefixed with {@code ->} (first) or {@code ^} (additional).
   * Body atoms are indented by variable-dependency depth.  Predicate labels containing
   * spaces are enclosed in single quotes so the output round-trips through the file parser.
   * When {@code noColor} is false and {@code config} is non-null, variable arguments are
   * wrapped in HTML {@code <span>} tags using the predicate-style map from the config.
   */
  private static void printAtomBlock(SWRLAPIRule rule, Map<IRI, String> labels,
      Map<String, String> prefixes, boolean noColor, SwrltabConfig config, String comment) {
    if (comment != null && !comment.isEmpty()) System.out.println("# " + comment);
    List<SWRLAtom> body = new ArrayList<>(rule.getBody());
    List<SWRLAtom> head = new ArrayList<>(rule.getHead());

    // Indent levels from variable dependencies (same algorithm as evaluateRuleBody)
    Map<String, Integer> firstMentionedBy = new LinkedHashMap<>();
    int[] indentLevels = new int[body.size()];
    for (int i = 0; i < body.size(); i++) {
      int parentIdx = -1;
      for (String v : getAtomVarNames(body.get(i))) {
        if (firstMentionedBy.containsKey(v))
          parentIdx = Math.max(parentIdx, firstMentionedBy.get(v));
      }
      indentLevels[i] = parentIdx >= 0 ? indentLevels[parentIdx] + 1 : 0;
      for (String v : getAtomVarNames(body.get(i)))
        firstMentionedBy.putIfAbsent(v, i);
    }

    for (int idx = 0; idx < body.size(); idx++) {
      String expr = formatAtomForListing(body.get(idx), labels, prefixes, noColor, config);
      String indent = "  ".repeat(indentLevels[idx]);
      System.out.println(indent + (idx == 0 ? "" : "^ ") + expr);
    }
    for (int idx = 0; idx < head.size(); idx++) {
      String expr = formatAtomForListing(head.get(idx), labels, prefixes, noColor, config);
      System.out.println((idx == 0 ? "-> " : "^  ") + expr);
    }
  }

  /**
   * Formats a SWRL atom as a plain-text expression for use in a .swrl file.
   * Predicate labels containing spaces are enclosed in double quotes.
   * Built-in predicates and named individuals use prefixed names (e.g. {@code swrlb:greaterThan})
   * or angle-bracket IRIs when no matching prefix is declared.
   */
  private static String formatAtomForFile(SWRLAtom atom, Map<IRI, String> labels,
      Map<String, String> prefixes) {
    if (atom instanceof SWRLClassAtom) {
      SWRLClassAtom ca = (SWRLClassAtom) atom;
      IRI iri = ca.getPredicate() instanceof OWLClass ? ((OWLClass) ca.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels) + "(" + fileIArg(ca.getArgument(), prefixes) + ")";
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      IRI iri = pa.getPredicate() instanceof OWLObjectProperty
          ? ((OWLObjectProperty) pa.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels)
          + "(" + fileIArg(pa.getFirstArgument(), prefixes)
          + ", " + fileIArg(pa.getSecondArgument(), prefixes) + ")";
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      IRI iri = da.getPredicate() instanceof OWLDataProperty
          ? ((OWLDataProperty) da.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels)
          + "(" + fileIArg(da.getFirstArgument(), prefixes)
          + ", " + fileDArg(da.getSecondArgument()) + ")";
    }
    if (atom instanceof SWRLBuiltInAtom) {
      SWRLBuiltInAtom ba = (SWRLBuiltInAtom) atom;
      String predName = iriToPrefixed(ba.getPredicate(), prefixes);
      List<String> args = new ArrayList<>();
      for (SWRLDArgument arg : ba.getArguments()) args.add(fileDArg(arg));
      return predName + "(" + String.join(", ", args) + ")";
    }
    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      return "differentFrom(" + fileIArg(dia.getFirstArgument(), prefixes)
          + ", " + fileIArg(dia.getSecondArgument(), prefixes) + ")";
    }
    return atom.getClass().getSimpleName();
  }

  /** Returns the rdfs:label (or IRI fragment) of {@code iri}, quoting it in single quotes if it contains spaces. */
  private static String quotedLabelOrFrag(IRI iri, Map<IRI, String> labels) {
    if (iri == null) return "?";
    String name = labelOrFrag(iri, labels);
    return name.contains(" ") ? "'" + name + "'" : name;
  }

/** Formats an individual argument for file output: {@code ?varName} or prefixed individual IRI. */
  private static String fileIArg(SWRLIArgument arg, Map<String, String> prefixes) {
    if (arg instanceof SWRLVariable)
      return "?" + iriFragment(((SWRLVariable) arg).getIRI().toString());
    if (arg instanceof OWLNamedIndividual)
      return iriToPrefixed(((OWLNamedIndividual) arg).getIRI(), prefixes);
    return arg.toString();
  }

  /** Formats a data argument for file output: {@code ?varName} or literal value. */
  private static String fileDArg(SWRLDArgument arg) {
    if (arg instanceof SWRLVariable)
      return "?" + iriFragment(((SWRLVariable) arg).getIRI().toString());
    if (arg instanceof OWLLiteral) return ((OWLLiteral) arg).getLiteral();
    return arg.toString();
  }

  /**
   * Like {@link #formatAtomForFile} but applies CSS-class {@code <span>} colouring to variable
   * arguments when {@code noColor} is false and {@code config} is non-null.
   * Colouring uses the predicate-style map from the config; variables for predicates not in the
   * map are left uncoloured.  When coloring is disabled, delegates directly to
   * {@link #formatAtomForFile}.
   */
  private static String formatAtomForListing(SWRLAtom atom, Map<IRI, String> labels,
      Map<String, String> prefixes, boolean noColor, SwrltabConfig config) {
    if (noColor || config == null) return formatAtomForFile(atom, labels, prefixes);

    String predName = atomPredName(atom, labels);
    List<String> styles = predName != null ? config.predicateStyles.get(predName) : null;

    if (atom instanceof SWRLClassAtom) {
      SWRLClassAtom ca = (SWRLClassAtom) atom;
      IRI iri = ca.getPredicate() instanceof OWLClass ? ((OWLClass) ca.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels)
          + "(" + fileIArgC(ca.getArgument(), prefixes, styles, 0, config) + ")";
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      IRI iri = pa.getPredicate() instanceof OWLObjectProperty
          ? ((OWLObjectProperty) pa.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels)
          + "(" + fileIArgC(pa.getFirstArgument(), prefixes, styles, 0, config)
          + ", " + fileIArgC(pa.getSecondArgument(), prefixes, styles, 1, config) + ")";
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      IRI iri = da.getPredicate() instanceof OWLDataProperty
          ? ((OWLDataProperty) da.getPredicate()).getIRI() : null;
      return quotedLabelOrFrag(iri, labels)
          + "(" + fileIArgC(da.getFirstArgument(), prefixes, styles, 0, config)
          + ", " + fileDArgC(da.getSecondArgument(), styles, 1, config) + ")";
    }
    if (atom instanceof SWRLBuiltInAtom) {
      SWRLBuiltInAtom ba = (SWRLBuiltInAtom) atom;
      String predBi = iriToPrefixed(ba.getPredicate(), prefixes);
      List<String> biStyles = config.predicateStyles.get(atomPredName(atom, labels));
      List<String> args = new ArrayList<>();
      List<SWRLDArgument> bArgs = ba.getArguments();
      for (int j = 0; j < bArgs.size(); j++) args.add(fileDArgC(bArgs.get(j), biStyles, j, config));
      return predBi + "(" + String.join(", ", args) + ")";
    }
    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      List<String> diStyles = config.predicateStyles.get("differentFrom");
      return "differentFrom("
          + fileIArgC(dia.getFirstArgument(), prefixes, diStyles, 0, config)
          + ", " + fileIArgC(dia.getSecondArgument(), prefixes, diStyles, 1, config) + ")";
    }
    return atom.getClass().getSimpleName();
  }

  /**
   * Formats an individual argument for listing output: wraps variable names in a CSS-class span
   * using the entity type at position {@code idx} in {@code styles} (repeating the last if shorter).
   * Named individuals are formatted as prefixed names.  Falls back to plain text if no style applies.
   */
  private static String fileIArgC(SWRLIArgument arg, Map<String, String> prefixes,
      List<String> styles, int idx, SwrltabConfig config) {
    if (arg instanceof SWRLVariable) {
      String varName = "?" + iriFragment(((SWRLVariable) arg).getIRI().toString());
      String eKey = styles != null && !styles.isEmpty() ? predStyle(styles, idx) : null;
      return classSpan(varName, eKey, config);
    }
    if (arg instanceof OWLNamedIndividual)
      return iriToPrefixed(((OWLNamedIndividual) arg).getIRI(), prefixes);
    return arg.toString();
  }

  /**
   * Formats a data argument for listing output: wraps variable names in a CSS-class span
   * using the entity type at position {@code idx} in {@code styles}.
   * Literals are rendered as their raw value.
   */
  private static String fileDArgC(SWRLDArgument arg, List<String> styles, int idx,
      SwrltabConfig config) {
    if (arg instanceof SWRLVariable) {
      String varName = "?" + iriFragment(((SWRLVariable) arg).getIRI().toString());
      String eKey = styles != null && !styles.isEmpty() ? predStyle(styles, idx) : null;
      return classSpan(varName, eKey, config);
    }
    if (arg instanceof OWLLiteral) return ((OWLLiteral) arg).getLiteral();
    return arg.toString();
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Renders a single SQWRL result value as a plain string.
   * Literals are rendered as their raw value (no XSD type suffix).
   * Named entities (individuals, classes, properties) use their short (local) name.
   */
  private static String renderValue(SQWRLResultValue v) throws SQWRLException {
    if (v.isLiteral()) {
      return v.asLiteralResult().getValue();
    } else if (v.isEntity()) {
      return v.asEntityResult().getShortName();
    }
    return v.toString();
  }

  /**
   * Joins a row of string cells with the given separator.
   * For CSV, cells that contain commas, quotes, or newlines are RFC 4180 quoted.
   */
  private static String joinRow(List<String> cells, char sep) {
    if (sep != ',') {
      return String.join("\t", cells);
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) sb.append(',');
      String cell = cells.get(i);
      if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
        sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
      } else {
        sb.append(cell);
      }
    }
    return sb.toString();
  }

  /**
   * Checks every class, object-property, and data-property term in the rule's body
   * and head for an explicit Declaration axiom in the ontology (including imports).
   * Terms that have no Declaration outside of the rule itself are reported as
   * potential errors (typos, removed terms, etc.).
   */
  private static void reportUndeclaredTerms(SWRLAPIRule rule, OWLOntology ontology) {
    Set<SWRLAtom> allAtoms = new HashSet<>(rule.getBody());
    allAtoms.addAll(rule.getHead());

    List<String> issues = new ArrayList<>();
    Set<IRI> checked = new HashSet<>();

    for (SWRLAtom atom : allAtoms) {
      OWLEntity entity = null;
      String kind = null;

      if (atom instanceof SWRLClassAtom) {
        OWLClassExpression ce = ((SWRLClassAtom) atom).getPredicate();
        if (ce instanceof OWLClass) { entity = (OWLClass) ce; kind = "Class"; }
      } else if (atom instanceof SWRLObjectPropertyAtom) {
        OWLObjectPropertyExpression pe = ((SWRLObjectPropertyAtom) atom).getPredicate();
        if (pe instanceof OWLObjectProperty) { entity = (OWLObjectProperty) pe; kind = "ObjectProperty"; }
      } else if (atom instanceof SWRLDataPropertyAtom) {
        OWLDataPropertyExpression pe = ((SWRLDataPropertyAtom) atom).getPredicate();
        if (pe instanceof OWLDataProperty) { entity = (OWLDataProperty) pe; kind = "DataProperty"; }
      }

      if (entity == null) continue;
      IRI iri = entity.getIRI();
      if (!checked.add(iri)) continue; // deduplicate
      if (!isDeclaredInClosure(entity, ontology)) {
        issues.add(kind + " " + iriFragment(iri.toString()) + " <" + iri + ">");
      }
    }

    if (!issues.isEmpty()) {
      System.err.println("# Debug WARNING: " + issues.size() + " undeclared term(s) in '"
          + rule.getRuleName() + "' — possible error:");
      for (String issue : issues) System.err.println("#   " + issue);
    }
  }

  /** Returns true if the entity has an explicit Declaration axiom anywhere in the import closure. */
  private static boolean isDeclaredInClosure(OWLEntity entity, OWLOntology ontology) {
    for (OWLOntology ont : ontology.getImportsClosure()) {
      if (!ont.getDeclarationAxioms(entity).isEmpty()) return true;
    }
    return false;
  }

  /**
   * Returns all possible new individual bindings for the first unbound variable in
   * {@code atom}, enabling the best-path search to explore every candidate.
   *
   * <ul>
   *   <li>{@code null} — atom type is delegated to {@link #evaluateAtom} (data properties,
   *       built-ins, etc.).</li>
   *   <li>Empty list — atom cannot be satisfied (fail).</li>
   *   <li>Single empty map — atom satisfied with no new individual bindings.</li>
   *   <li>Multiple maps — one per candidate individual for the unbound variable.</li>
   * </ul>
   */
  private static List<Map<IRI, IRI>> allCandidateIndBindings(SWRLAtom atom,
      Map<IRI, IRI> indBindings, OWLOntology ontology, OWLDataFactory df) {

    if (atom instanceof SWRLClassAtom) {
      SWRLClassAtom ca = (SWRLClassAtom) atom;
      OWLClassExpression ce = ca.getPredicate();
      IRI boundIRI = resolveIArg(ca.getArgument(), indBindings);
      if (boundIRI != null) {
        OWLNamedIndividual ind = df.getOWLNamedIndividual(boundIRI);
        for (OWLOntology ont : ontology.getImportsClosure())
          for (OWLClassAssertionAxiom ax : ont.getClassAssertionAxioms(ind))
            if (ax.getClassExpression().equals(ce))
              return Collections.singletonList(Collections.emptyMap());
        return Collections.emptyList();
      }
      IRI varIRI = (ca.getArgument() instanceof SWRLVariable)
          ? ((SWRLVariable) ca.getArgument()).getIRI() : null;
      Set<IRI> seen = new HashSet<>();
      List<Map<IRI, IRI>> result = new ArrayList<>();
      for (OWLOntology ont : ontology.getImportsClosure())
        for (OWLClassAssertionAxiom ax : ont.getAxioms(AxiomType.CLASS_ASSERTION))
          if (ax.getClassExpression().equals(ce) && ax.getIndividual() instanceof OWLNamedIndividual) {
            IRI candIRI = ((OWLNamedIndividual) ax.getIndividual()).getIRI();
            if (seen.add(candIRI)) {
              Map<IRI, IRI> m = new LinkedHashMap<>();
              if (varIRI != null) m.put(varIRI, candIRI);
              result.add(m);
            }
          }
      return result;
    }

    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      OWLObjectPropertyExpression pe = pa.getPredicate();
      IRI subjIRI = resolveIArg(pa.getFirstArgument(), indBindings);
      IRI objIRI  = resolveIArg(pa.getSecondArgument(), indBindings);

      if (subjIRI == null) return null; // subject unbound: delegate

      OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
      if (objIRI != null) {
        // Both bound: verify
        OWLNamedIndividual obj = df.getOWLNamedIndividual(objIRI);
        for (OWLOntology ont : ontology.getImportsClosure())
          for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(subj))
            if (ax.getProperty().equals(pe) && ax.getObject().equals(obj))
              return Collections.singletonList(Collections.emptyMap());
        return Collections.emptyList();
      }

      // Object unbound: enumerate
      IRI objVar = (pa.getSecondArgument() instanceof SWRLVariable)
          ? ((SWRLVariable) pa.getSecondArgument()).getIRI() : null;
      Set<IRI> seen = new HashSet<>();
      List<Map<IRI, IRI>> result = new ArrayList<>();
      for (OWLOntology ont : ontology.getImportsClosure())
        for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(subj))
          if (ax.getProperty().equals(pe) && ax.getObject() instanceof OWLNamedIndividual) {
            IRI candIRI = ((OWLNamedIndividual) ax.getObject()).getIRI();
            if (seen.add(candIRI)) {
              Map<IRI, IRI> m = new LinkedHashMap<>();
              if (objVar != null) m.put(objVar, candIRI);
              result.add(m);
            }
          }
      return result;
    }

    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      IRI a = resolveIArg(dia.getFirstArgument(), indBindings);
      IRI b = resolveIArg(dia.getSecondArgument(), indBindings);
      if (a != null && b != null)
        return a.equals(b) ? Collections.emptyList()
                           : Collections.singletonList(Collections.emptyMap());
      return null; // unbound: delegate (evaluateAtom will skip)
    }

    return null; // DataProperty, BuiltIn, etc.: delegate to evaluateAtom
  }

  /**
   * Recursively searches the body atom list starting at {@code idx}, exploring up to
   * {@link #MAX_BRANCH} candidates at each multi-match atom.  Returns the
   * {@link PathResult} — the variable bindings and score — for the highest-scoring
   * path found.
   */
  private static PathResult searchPaths(List<SWRLAtom> atoms, int idx,
      Map<IRI, IRI> indBindings, Map<IRI, OWLLiteral> litBindings,
      OWLOntology ontology, OWLDataFactory df) {

    if (idx >= atoms.size()) {
      PathResult pr = new PathResult();
      pr.indBindings = new LinkedHashMap<>(indBindings);
      pr.litBindings = new LinkedHashMap<>(litBindings);
      return pr;
    }

    SWRLAtom atom = atoms.get(idx);
    List<Map<IRI, IRI>> candidates = allCandidateIndBindings(atom, indBindings, ontology, df);

    if (candidates == null) {
      // Non-branching atom: use evaluateAtom for its single outcome
      AtomResult ar = evaluateAtom(atom, indBindings, litBindings, ontology, df);
      if (ar.skip) {
        return searchPaths(atoms, idx + 1, indBindings, litBindings, ontology, df);
      }
      Map<IRI, IRI> newInd = new LinkedHashMap<>(indBindings);
      newInd.putAll(ar.newIndBindings);
      Map<IRI, OWLLiteral> newLit = new LinkedHashMap<>(litBindings);
      newLit.putAll(ar.newLitBindings);
      PathResult rest = searchPaths(atoms, idx + 1, newInd, newLit, ontology, df);
      if (ar.satisfied) rest.score++;
      return rest;
    }

    if (candidates.isEmpty()) {
      // Atom fails: no new bindings, continue without score increment
      return searchPaths(atoms, idx + 1, indBindings, litBindings, ontology, df);
    }

    // Multiple candidates: explore each, keep best; stop early if complete solution found
    PathResult best = null;
    int limit = Math.min(candidates.size(), MAX_BRANCH);
    for (int c = 0; c < limit; c++) {
      Map<IRI, IRI> newInd = new LinkedHashMap<>(indBindings);
      newInd.putAll(candidates.get(c));
      PathResult rest = searchPaths(atoms, idx + 1, newInd, litBindings, ontology, df);
      rest.score++;
      if (best == null || rest.score > best.score) best = rest;
      if (best.score == atoms.size()) break; // complete solution — no need to explore further
    }
    return (best != null) ? best : new PathResult();
  }

  /**
   * Walks the body atoms of {@code rule} in order, evaluating each against the ontology
   * given the starting variable bindings from constraints.  Prints a PASS/FAIL line per
   * atom and a final score on stderr.  Bindings discovered during evaluation are
   * propagated greedily (first match) to subsequent atoms.
   */
  // ---------------------------------------------------------------------------
  // Configuration: entity styles and predicate argument colours
  // ---------------------------------------------------------------------------

  /** Parsed definition of a single entity-type style entry from the config file. */
  private static class EntityStyleDef {
    final String element;   // HTML element type, e.g. "span"
    final String cssBlock;  // CSS property block without braces, e.g. "color: blue;"
    EntityStyleDef(String element, String cssBlock) {
      this.element = element;
      this.cssBlock = cssBlock;
    }
  }

  /** Loaded configuration: entity styles and predicate argument styles. */
  private static class SwrltabConfig {
    Map<String, EntityStyleDef> entityStyles    = new LinkedHashMap<>();
    Map<String, List<String>>   predicateStyles = new LinkedHashMap<>();
  }

  /**
   * Returns the built-in default configuration.  To customise styles create
   * {@code ~/.swrltab/swrltab_config.yaml} (global) or
   * {@code swrltab_config.yaml} alongside the ontology file (per-project).
   */
  private static SwrltabConfig defaultConfig() {
    SwrltabConfig c = new SwrltabConfig();
    c.entityStyles.put("process",              new EntityStyleDef("span", "color: blue"));
    c.entityStyles.put("material",             new EntityStyleDef("span", "color: green"));
    c.entityStyles.put("characteristic",       new EntityStyleDef("span", "color: tan"));
    c.entityStyles.put("characteristic_value", new EntityStyleDef("span", "color: saddlebrown"));
    c.entityStyles.put("information",          new EntityStyleDef("span", "color: dimgray"));
    c.entityStyles.put("error",                new EntityStyleDef("span", "color: red"));
    c.predicateStyles.put("has specified input",      Arrays.asList("process",              "material"));
    c.predicateStyles.put("has specified output",     Arrays.asList("process",              "material"));
    c.predicateStyles.put("has characteristic",       Arrays.asList("material",             "characteristic"));
    c.predicateStyles.put("has characteristic value", Arrays.asList("characteristic",       "characteristic_value"));
    c.predicateStyles.put("has quantity",             Arrays.asList("characteristic_value", "information"));
    c.predicateStyles.put("mass in kilograms",        Arrays.asList("characteristic_value"));
    c.predicateStyles.put("combining two materials",  Arrays.asList("process"));
    c.predicateStyles.put("swrlb:add",                Arrays.asList("information"));
    c.predicateStyles.put("temperature",              Arrays.asList("characteristic"));
    c.predicateStyles.put("characteristic value of",  Arrays.asList("characteristic_value", "characteristic"));
    return c;
  }

  /** Overlays non-empty entries from {@code overlay} onto {@code base} in place. */
  private static void mergeConfig(SwrltabConfig base, SwrltabConfig overlay) {
    base.entityStyles.putAll(overlay.entityStyles);
    base.predicateStyles.putAll(overlay.predicateStyles);
  }

  /**
   * Parses a minimal YAML subset: two top-level sections ({@code entity_styles} and
   * {@code predicate_styles}), each containing indented {@code key: value} or
   * {@code key: [list]} entries.  Keys and string values may be double-quoted.
   */
  private static SwrltabConfig parseConfigFile(File file) throws IOException {
    SwrltabConfig c = new SwrltabConfig();
    String section = null;
    for (String rawLine : Files.readAllLines(file.toPath())) {
      if (rawLine.trim().isEmpty() || rawLine.trim().startsWith("#")) continue;
      if (!rawLine.startsWith(" ") && !rawLine.startsWith("\t")) {
        String t = rawLine.trim();
        section = t.equals("entity_styles:") ? "entity_styles"
                : t.equals("predicate_styles:") ? "predicate_styles" : null;
        continue;
      }
      if (section == null) continue;
      String trimmed = rawLine.trim();
      String key, value;
      if (trimmed.startsWith("\"")) {
        int closeQ = trimmed.indexOf('"', 1);
        if (closeQ < 0) continue;
        key = trimmed.substring(1, closeQ);
        int colon = trimmed.indexOf(':', closeQ + 1);
        if (colon < 0) continue;
        value = trimmed.substring(colon + 1).trim();
      } else {
        int colon = trimmed.indexOf(':');
        if (colon < 0) continue;
        key = trimmed.substring(0, colon).trim();
        value = trimmed.substring(colon + 1).trim();
      }
      if ("entity_styles".equals(section)) {
        EntityStyleDef def = parseEntityStyleDef(value);
        if (def != null) c.entityStyles.put(key, def);
      } else {
        List<String> styles = parseStringList(value);
        if (styles != null) c.predicateStyles.put(key, styles);
      }
    }
    return c;
  }

  /** Parses a value such as {@code "span {color: blue;}"} into an {@link EntityStyleDef}. */
  private static EntityStyleDef parseEntityStyleDef(String value) {
    if (value.startsWith("\"") && value.endsWith("\""))
      value = value.substring(1, value.length() - 1);
    else if (value.startsWith("'") && value.endsWith("'"))
      value = value.substring(1, value.length() - 1);
    Matcher m = Pattern.compile("^(\\w+)\\s*\\{([^}]*)\\}").matcher(value.trim());
    return m.find() ? new EntityStyleDef(m.group(1), m.group(2).trim()) : null;
  }

  /** Parses a YAML inline list such as {@code [process, material]}. */
  private static List<String> parseStringList(String value) {
    value = value.trim();
    if (!value.startsWith("[") || !value.endsWith("]")) return null;
    value = value.substring(1, value.length() - 1);
    List<String> result = new ArrayList<>();
    for (String item : value.split(",")) {
      String t = item.trim();
      if (!t.isEmpty()) result.add(t);
    }
    return result.isEmpty() ? null : result;
  }

  /**
   * Loads the effective configuration by merging (in order): built-in defaults,
   * {@code ~/.swrltab/swrltab_config.yaml} (global), {@code swrltab_config.yaml}
   * in the ontology directory (per-project), then the {@code --config} path if given.
   * Later layers win.
   */
  private static SwrltabConfig loadConfig(File ontologyDir, String explicitPath) {
    SwrltabConfig config = defaultConfig();
    File globalFile = new File(System.getProperty("user.home"), ".swrltab/swrltab_config.yaml");
    if (globalFile.exists()) tryMergeConfig(config, globalFile);
    if (ontologyDir != null) {
      File projectFile = new File(ontologyDir, "swrltab_config.yaml");
      if (projectFile.exists()) tryMergeConfig(config, projectFile);
    }
    if (explicitPath != null) {
      File explicit = new File(explicitPath);
      if (explicit.exists()) tryMergeConfig(config, explicit);
      else System.err.println("Warning: --config file not found: " + explicit.getAbsolutePath());
    }
    return config;
  }

  private static void tryMergeConfig(SwrltabConfig base, File file) {
    try {
      mergeConfig(base, parseConfigFile(file));
    } catch (IOException e) {
      System.err.println("Warning: could not read config " + file + ": " + e.getMessage());
    }
  }

  /** Generates CSS class definitions from entity style entries in {@code config}. */
  private static String generateEntityCSS(SwrltabConfig config) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, EntityStyleDef> e : config.entityStyles.entrySet())
      sb.append(".").append(e.getKey()).append(" { ").append(e.getValue().cssBlock).append("; white-space: nowrap }\n");
    return sb.toString().trim();
  }

  /** Returns a one-line HTML legend with each entity style name rendered in its own colour. */
  private static String buildColorLegend(SwrltabConfig config) {
    if (config.entityStyles.isEmpty()) return "";
    List<String> parts = new ArrayList<>();
    for (String key : config.entityStyles.keySet())
      parts.add(classSpan(key, key, config));
    return "**Legend:** " + String.join(" &nbsp;·&nbsp; ", parts);
  }

  /**
   * Wraps {@code text} in the HTML element defined for {@code entityKey} in {@code config}.
   * When {@code entityKey} is null or has no entry in {@code config}, falls back to a plain
   * {@code <span style="white-space: nowrap">} so that variable identifiers never break
   * across lines even when no colour class is assigned.
   */
  private static String classSpan(String text, String entityKey, SwrltabConfig config) {
    if (entityKey != null) {
      EntityStyleDef def = config.entityStyles.get(entityKey);
      if (def != null)
        return "<" + def.element + " class=\"" + entityKey + "\">" + text + "</" + def.element + ">";
    }
    return "<span style=\"white-space: nowrap\">" + text + "</span>";
  }

  /** Returns the entity type key for argument position {@code idx},
   *  repeating the last entry if the list is shorter than the atom's arity. */
  private static String predStyle(List<String> styles, int idx) {
    return styles.get(Math.min(idx, styles.size() - 1));
  }

  /**
   * Colorizes a single argument value for the match column.
   * Unbound variable tokens (those beginning with {@code ?}) receive the {@code error} class
   * (rendered red); bound values receive their entity-type class when one is defined.
   */
  private static String colorizeArgValue(String value, String entityKey, SwrltabConfig config) {
    if (value.startsWith("?")) return classSpan(value, "error", config);
    if (entityKey != null) return classSpan(value, entityKey, config);
    return value;
  }

  /** Returns the predicate display name (rdfs:label or IRI fragment) for any SWRL atom type. */
  private static String atomPredName(SWRLAtom atom, Map<IRI, String> labels) {
    if (atom instanceof SWRLClassAtom) {
      OWLClassExpression ce = ((SWRLClassAtom) atom).getPredicate();
      return (ce instanceof OWLClass) ? labelOrFrag(((OWLClass) ce).getIRI(), labels) : null;
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      OWLObjectPropertyExpression pe = ((SWRLObjectPropertyAtom) atom).getPredicate();
      return (pe instanceof OWLObjectProperty) ? labelOrFrag(((OWLObjectProperty) pe).getIRI(), labels) : null;
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      OWLDataPropertyExpression pe = ((SWRLDataPropertyAtom) atom).getPredicate();
      return (pe instanceof OWLDataProperty) ? labelOrFrag(((OWLDataProperty) pe).getIRI(), labels) : null;
    }
    if (atom instanceof SWRLBuiltInAtom) {
      IRI predIRI = ((SWRLBuiltInAtom) atom).getPredicate();
      String frag = iriFragment(predIRI.toString());
      // Return "swrlb:name" for standard SWRL built-ins so config keys like "swrlb:add" work
      return predIRI.toString().startsWith("http://www.w3.org/2003/11/swrlb#")
          ? "swrlb:" + frag : frag;
    }
    if (atom instanceof SWRLDifferentIndividualsAtom) return "differentFrom";
    return null;
  }

  /**
   * Returns the colorized variable names for all variable arguments of {@code atom}.
   * Bound variables receive their positional entity-type class; unbound receive "error".
   */
  private static List<String> colorizeAtomVars(SWRLAtom atom, Map<IRI, IRI> indBindings,
      Map<IRI, OWLLiteral> litBindings, Map<IRI, String> labels, SwrltabConfig config) {
    String predName = atomPredName(atom, labels);
    List<String> styles = predName != null ? config.predicateStyles.get(predName) : null;
    List<String> result = new ArrayList<>();
    if (atom instanceof SWRLClassAtom) {
      addColoredVar(result, ((SWRLClassAtom) atom).getArgument(), 0, indBindings, litBindings, styles, config);
    } else if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      addColoredVar(result, pa.getFirstArgument(),  0, indBindings, litBindings, styles, config);
      addColoredVar(result, pa.getSecondArgument(), 1, indBindings, litBindings, styles, config);
    } else if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      addColoredVar(result, da.getFirstArgument(),  0, indBindings, litBindings, styles, config);
      addColoredVar(result, da.getSecondArgument(), 1, indBindings, litBindings, styles, config);
    } else if (atom instanceof SWRLBuiltInAtom) {
      List<SWRLDArgument> args = ((SWRLBuiltInAtom) atom).getArguments();
      for (int j = 0; j < args.size(); j++)
        addColoredVar(result, args.get(j), j, indBindings, litBindings, styles, config);
    } else if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      addColoredVar(result, dia.getFirstArgument(),  0, indBindings, litBindings, styles, config);
      addColoredVar(result, dia.getSecondArgument(), 1, indBindings, litBindings, styles, config);
    }
    return result;
  }

  /**
   * If {@code arg} is a SWRL variable, appends its colorized name to {@code result}.
   * Bound variables receive their entity-type class (or no class if none is defined);
   * unbound variables receive the {@code error} class.
   */
  private static void addColoredVar(List<String> result, SWRLArgument arg, int argIdx,
      Map<IRI, IRI> indBindings, Map<IRI, OWLLiteral> litBindings,
      List<String> styles, SwrltabConfig config) {
    if (!(arg instanceof SWRLVariable)) return;
    IRI varIRI = ((SWRLVariable) arg).getIRI();
    String varName = "?" + iriFragment(varIRI.toString());
    boolean bound = indBindings.containsKey(varIRI) || litBindings.containsKey(varIRI);
    if (bound) {
      String key = (styles != null && !styles.isEmpty()) ? predStyle(styles, argIdx) : null;
      result.add(classSpan(varName, key, config));
    } else {
      result.add(classSpan(varName, "error", config));
    }
  }

  /**
   * Formats a SWRL atom for display, applying per-argument HTML class spans
   * when {@code colorize} is true and the predicate appears in the config's
   * predicate styles map.  Bound variable values are substituted inline.
   */
  private static String formatAtomColored(SWRLAtom atom, Map<IRI, IRI> indBindings,
      Map<IRI, OWLLiteral> litBindings, Map<IRI, String> labels, boolean colorize,
      SwrltabConfig config) {
    if (atom instanceof SWRLClassAtom) {
      SWRLClassAtom ca = (SWRLClassAtom) atom;
      IRI predIRI = (ca.getPredicate() instanceof OWLClass)
          ? ((OWLClass) ca.getPredicate()).getIRI() : null;
      String predName = labelOrFrag(predIRI, labels);
      List<String> styles = colorize ? config.predicateStyles.get(predName) : null;
      String a0 = fmtIArg(ca.getArgument(), indBindings, labels);
      if (colorize) a0 = colorizeArgValue(a0, styles != null && !styles.isEmpty() ? predStyle(styles, 0) : null, config);
      return predName + "\t(" + a0 + ")";
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      IRI predIRI = (pa.getPredicate() instanceof OWLObjectProperty)
          ? ((OWLObjectProperty) pa.getPredicate()).getIRI() : null;
      String predName = labelOrFrag(predIRI, labels);
      List<String> styles = colorize ? config.predicateStyles.get(predName) : null;
      String a0 = fmtIArg(pa.getFirstArgument(), indBindings, labels);
      String a1 = fmtIArg(pa.getSecondArgument(), indBindings, labels);
      if (colorize) {
        a0 = colorizeArgValue(a0, styles != null && !styles.isEmpty() ? predStyle(styles, 0) : null, config);
        a1 = colorizeArgValue(a1, styles != null && !styles.isEmpty() ? predStyle(styles, 1) : null, config);
      }
      return predName + "\t(" + a0 + ", " + a1 + ")";
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      IRI predIRI = (da.getPredicate() instanceof OWLDataProperty)
          ? ((OWLDataProperty) da.getPredicate()).getIRI() : null;
      String predName = labelOrFrag(predIRI, labels);
      List<String> styles = colorize ? config.predicateStyles.get(predName) : null;
      String a0 = fmtIArg(da.getFirstArgument(), indBindings, labels);
      String a1 = fmtDArg(da.getSecondArgument(), litBindings);
      if (colorize) {
        a0 = colorizeArgValue(a0, styles != null && !styles.isEmpty() ? predStyle(styles, 0) : null, config);
        a1 = colorizeArgValue(a1, styles != null && !styles.isEmpty() ? predStyle(styles, 1) : null, config);
      }
      return predName + "\t(" + a0 + ", " + a1 + ")";
    }
    if (atom instanceof SWRLBuiltInAtom) {
      SWRLBuiltInAtom ba = (SWRLBuiltInAtom) atom;
      String predName = atomPredName(atom, labels); // returns "swrlb:name" for standard built-ins
      List<String> styles = colorize ? config.predicateStyles.get(predName) : null;
      predName = iriFragment(ba.getPredicate().toString()); // display name uses just the fragment
      List<String> parts = new ArrayList<>();
      List<SWRLDArgument> bArgs = ba.getArguments();
      for (int j = 0; j < bArgs.size(); j++) {
        String val = fmtDArg(bArgs.get(j), litBindings);
        if (colorize) val = colorizeArgValue(val, styles != null && !styles.isEmpty() ? predStyle(styles, j) : null, config);
        parts.add(val);
      }
      return predName + "\t(" + String.join(", ", parts) + ")";
    }
    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      String predName = "differentFrom";
      List<String> styles = colorize ? config.predicateStyles.get(predName) : null;
      String a0 = fmtIArg(dia.getFirstArgument(), indBindings, labels);
      String a1 = fmtIArg(dia.getSecondArgument(), indBindings, labels);
      if (colorize) {
        a0 = colorizeArgValue(a0, styles != null && !styles.isEmpty() ? predStyle(styles, 0) : null, config);
        a1 = colorizeArgValue(a1, styles != null && !styles.isEmpty() ? predStyle(styles, 1) : null, config);
      }
      return predName + "\t(" + a0 + ", " + a1 + ")";
    }
    return atom.getClass().getSimpleName();
  }

  // ---------------------------------------------------------------------------

  private static void evaluateRuleBody(SWRLAPIRule rule, OWLOntology ontology,
      Map<IRI, IRI> initialIndBindings, Map<IRI, String> labels,
      Set<SWRLAtom> matchedAtoms, Map<IRI, Set<IRI>> inverseMap, String format, boolean noColor,
      SwrltabConfig config) {
    OWLDataFactory df = ontology.getOWLOntologyManager().getOWLDataFactory();
    List<SWRLAtom> body = new ArrayList<>(rule.getBody());
    Map<IRI, IRI>        indBindings = new LinkedHashMap<>(initialIndBindings);
    Map<IRI, OWLLiteral> litBindings = new LinkedHashMap<>();
    int passed = 0;

    // Variables that appear in the head are shown there with resolved values,
    // so suppress their discovery lines in the body evaluation.
    Set<String> headVarFrags = new HashSet<>();
    for (SWRLAtom a : rule.getHead())
      for (String n : getAtomVarNames(a))
        headVarFrags.add(n.substring(1)); // strip leading "?"

    boolean md       = format.equals("markdown");
    boolean colorize = md && !noColor;

    // --- Section header + column header row ---
    if (md) {
      System.out.println("## Rule: " + rule.getRuleName());
      System.out.println();
      System.out.println(mdRow(Arrays.asList("row", "status", "predicate", "variables", "match", "notes")));
      System.out.println(mdSep(6));
    } else {
      System.out.println("# Rule: " + rule.getRuleName());
      System.out.println("  row\tstatus\tpredicate\tvariables\tmatch\tnotes");
    }

    // --- Indent levels: atom i is indented one level deeper than the latest preceding
    //     atom that shares a variable with it (i.e., its closest dependency).
    Map<String, Integer> firstMentionedBy = new LinkedHashMap<>();
    int[] indentLevels = new int[body.size()];
    for (int i = 0; i < body.size(); i++) {
      int parentIdx = -1;
      for (String v : getAtomVarNames(body.get(i))) {
        if (firstMentionedBy.containsKey(v))
          parentIdx = Math.max(parentIdx, firstMentionedBy.get(v));
      }
      indentLevels[i] = parentIdx >= 0 ? indentLevels[parentIdx] + 1 : 0;
      for (String v : getAtomVarNames(body.get(i)))
        firstMentionedBy.putIfAbsent(v, i);
    }

    // --- Body atom loop ---
    for (int idx = 0; idx < body.size(); idx++) {
      SWRLAtom atom = body.get(idx);
      List<String> varNames = getAtomVarNames(atom);
      AtomResult r = evaluateAtom(atom, indBindings, litBindings, ontology, df);

      // Update bindings first so the display shows resolved values inline.
      // Only candidate-count notes go to detail sub-rows; binding values appear inline.
      List<String> details = new ArrayList<>();
      if (r.satisfied) {
        passed++;
        indBindings.putAll(r.newIndBindings);
        litBindings.putAll(r.newLitBindings);
        if (r.candidateCount > 1) {
          for (Map.Entry<IRI, IRI> e : r.newIndBindings.entrySet()) {
            String frag = iriFragment(e.getKey().toString());
            if (!headVarFrags.contains(frag))
              details.add("?" + frag + ": first of " + r.candidateCount + " match(es)");
          }
        }
      }

      // Format atom using post-eval bindings so newly bound values show inline.
      // Per-argument colours come from the predicate styles config when colorize is true.
      String display = formatAtomColored(atom, indBindings, litBindings, labels, colorize, config);

      // Compute notes column: MATCHED > INVERSE ONLY > diagnostic note from evaluation
      String noteCell;
      if (matchedAtoms.contains(atom)) {
        noteCell = "MATCHED";
      } else if (!r.satisfied && !r.skip && atom instanceof SWRLObjectPropertyAtom
          && checkInverseOnly((SWRLObjectPropertyAtom) atom, indBindings, inverseMap, ontology, df)) {
        noteCell = "INVERSE ONLY";
      } else if (!r.note.isEmpty()) {
        noteCell = md ? "*" + r.note + "*" : r.note;
      } else {
        noteCell = "";
      }

      // Render row
      String mdIndent  = "&nbsp;&nbsp;&nbsp;&nbsp;".repeat(indentLevels[idx]);
      String txtIndent = "    ".repeat(indentLevels[idx]);
      if (md) {
        String[] parts = display.split("\t", 2);
        String predCell  = "<span style=\"white-space: nowrap\">" + mdIndent + parts[0] + "</span>";
        String matchCell = parts.length > 1 ? parts[1] : "";
        List<String> varDisplays = (colorize && !varNames.isEmpty())
            ? colorizeAtomVars(atom, indBindings, litBindings, labels, config) : varNames;
        String varCell = varDisplays.isEmpty() ? "" : String.join(", ", varDisplays);
        String statusStr = r.skip ? "SKIP" : (r.satisfied
            ? "<span style=\"color: green;\">PASS</span>" : "FAIL");
        System.out.println(mdRow(Arrays.asList(
            String.valueOf(idx + 1), statusStr, predCell, varCell, matchCell, noteCell)));
        for (String d : details)
          System.out.println(mdRow(Arrays.asList("", "", "", "", "*" + d + "*", "")));
      } else {
        String[] parts   = display.split("\t", 2);
        String predCell  = txtIndent + parts[0];
        String matchCell = parts.length > 1 ? parts[1] : "";
        String varCell   = varNames.isEmpty() ? "" : "(" + String.join(", ", varNames) + ")";
        String statusStr = r.skip ? "SKIP" : (r.satisfied ? "PASS" : "FAIL");
        System.out.printf("  %2d\t%s\t%s\t%s\t%s\t%s%n",
            idx + 1, statusStr, predCell, varCell, matchCell, noteCell);
        for (String d : details)
          System.out.println("\t\t\t\t" + d);
      }
    }

    // --- Head (consequent) atoms ---
    List<SWRLAtom> head = new ArrayList<>(rule.getHead());
    String headStatus = (passed == body.size())
        ? (md ? "<span style=\"color: green;\">PASS</span>" : "PASS") : "SKIP";
    if (!head.isEmpty()) {
      if (md) {
        for (int idx = 0; idx < head.size(); idx++) {
          SWRLAtom atom = head.get(idx);
          String display = formatAtomColored(atom, indBindings, litBindings, labels, colorize, config);
          String[] parts = display.split("\t", 2);
          List<String> hvn = getAtomVarNames(atom);
          String hvnStr = hvn.isEmpty() ? "" : (colorize
              ? String.join(", ", colorizeAtomVars(atom, indBindings, litBindings, labels, config))
              : String.join(", ", hvn));
          String predPrefix = idx == 0 ? "→ " : "&nbsp;&nbsp;";
          System.out.println(mdRow(Arrays.asList(
              String.valueOf(body.size() + idx + 1), headStatus,
              "<span style=\"white-space: nowrap\">" + predPrefix + parts[0] + "</span>",
              hvnStr,
              parts.length > 1 ? parts[1] : "",
              "")));
        }
      } else {
        for (int idx = 0; idx < head.size(); idx++) {
          SWRLAtom atom = head.get(idx);
          String display = formatAtomColored(atom, indBindings, litBindings, labels, false, config);
          String[] hparts = display.split("\t", 2);
          List<String> hvn = getAtomVarNames(atom);
          String varCell = hvn.isEmpty() ? "" : "(" + String.join(", ", hvn) + ")";
          String predPrefix = idx == 0 ? "-> " : "   ";
          System.out.printf("  %2d\t%s\t%s\t%s\t%s%n",
              body.size() + idx + 1, headStatus, predPrefix + hparts[0], varCell,
              hparts.length > 1 ? hparts[1] : "");
        }
      }
    }

    // --- Score ---
    String scoreMsg = passed + "/" + body.size() + " body atoms satisfied — rule "
        + (passed == body.size() ? "should have fired" : "likely did not fire");
    if (md) {
      System.out.println();
      System.out.println("**Score: " + scoreMsg + "**");
    } else {
      System.out.println("# Score: " + scoreMsg);
    }
  }

  /**
   * Evaluates a single SWRL body atom given current variable bindings.
   * New bindings (for previously unbound variables) are returned in the result.
   * The caller is responsible for merging them into the active binding maps.
   */
  private static AtomResult evaluateAtom(SWRLAtom atom,
      Map<IRI, IRI> indBindings, Map<IRI, OWLLiteral> litBindings,
      OWLOntology ontology, OWLDataFactory df) {
    AtomResult r = new AtomResult();

    // --- ClassAtom C(?x) ---
    if (atom instanceof SWRLClassAtom) {
      OWLClassExpression ce = ((SWRLClassAtom) atom).getPredicate();
      SWRLIArgument arg = ((SWRLClassAtom) atom).getArgument();
      IRI boundIRI = resolveIArg(arg, indBindings);
      if (boundIRI != null) {
        // Variable already bound — verify ClassAssertion exists
        OWLNamedIndividual ind = df.getOWLNamedIndividual(boundIRI);
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLClassAssertionAxiom ax : ont.getClassAssertionAxioms(ind)) {
            if (ax.getClassExpression().equals(ce)) { r.satisfied = true; return r; }
          }
        }
        r.note = "no ClassAssertion(" + iriFragment(ce.toString()) + ", "
            + iriFragment(boundIRI.toString()) + ") in ontology";
      } else {
        // Unbound — find candidates
        IRI varIRI = unboundVar(arg, indBindings, litBindings);
        List<OWLNamedIndividual> candidates = new ArrayList<>();
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLClassAssertionAxiom ax : ont.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (ax.getClassExpression().equals(ce)
                && ax.getIndividual() instanceof OWLNamedIndividual
                && !candidates.contains(ax.getIndividual()))
              candidates.add((OWLNamedIndividual) ax.getIndividual());
          }
        }
        if (candidates.isEmpty()) {
          r.note = "no individuals asserted as " + iriFragment(ce.toString());
        } else {
          r.satisfied = true;
          r.candidateCount = candidates.size();
          if (varIRI != null) r.newIndBindings.put(varIRI, candidates.get(0).getIRI());
        }
      }
      return r;
    }

    // --- ObjectPropertyAtom R(?x, ?y) ---
    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      OWLObjectPropertyExpression pe = pa.getPredicate();
      IRI subjIRI = resolveIArg(pa.getFirstArgument(), indBindings);
      IRI objIRI  = resolveIArg(pa.getSecondArgument(), indBindings);

      if (subjIRI != null && objIRI != null) {
        // Both bound — check exact assertion
        OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
        OWLNamedIndividual obj  = df.getOWLNamedIndividual(objIRI);
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(subj)) {
            if (ax.getProperty().equals(pe) && ax.getObject().equals(obj)) {
              r.satisfied = true; return r;
            }
          }
        }
        r.note = "no ObjectPropertyAssertion(" + iriFragment(pe.toString()) + ", "
            + iriFragment(subjIRI.toString()) + ", " + iriFragment(objIRI.toString()) + ")";
      } else if (subjIRI != null) {
        // Subject bound, object unbound — find objects
        OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
        IRI objVar = unboundVar(pa.getSecondArgument(), indBindings, litBindings);
        List<OWLNamedIndividual> candidates = new ArrayList<>();
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(subj)) {
            if (ax.getProperty().equals(pe) && ax.getObject() instanceof OWLNamedIndividual)
              candidates.add((OWLNamedIndividual) ax.getObject());
          }
        }
        if (candidates.isEmpty()) {
          r.note = "no ObjectPropertyAssertion(" + iriFragment(pe.toString()) + ", "
              + iriFragment(subjIRI.toString()) + ", ?)";
        } else {
          r.satisfied = true;
          r.candidateCount = candidates.size();
          if (objVar != null) r.newIndBindings.put(objVar, candidates.get(0).getIRI());
        }
      } else if (objIRI != null) {
        // Object bound, subject unbound — find subjects
        OWLNamedIndividual obj = df.getOWLNamedIndividual(objIRI);
        IRI subjVar = unboundVar(pa.getFirstArgument(), indBindings, litBindings);
        List<OWLNamedIndividual> candidates = new ArrayList<>();
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLAxiom ax : ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
            OWLObjectPropertyAssertionAxiom opa = (OWLObjectPropertyAssertionAxiom) ax;
            if (opa.getProperty().equals(pe) && opa.getObject().equals(obj)
                && opa.getSubject() instanceof OWLNamedIndividual)
              candidates.add((OWLNamedIndividual) opa.getSubject());
          }
        }
        if (candidates.isEmpty()) {
          r.note = "no ObjectPropertyAssertion(" + iriFragment(pe.toString())
              + ", ?, " + iriFragment(objIRI.toString()) + ")";
        } else {
          r.satisfied = true;
          r.candidateCount = candidates.size();
          if (subjVar != null) r.newIndBindings.put(subjVar, candidates.get(0).getIRI());
        }
      } else {
        // Both unbound — skip (too broad for greedy evaluation)
        r.skip = true;
        r.note = "both arguments unbound";
      }
      return r;
    }

    // --- DataPropertyAtom D(?x, ?v) ---
    if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      OWLDataPropertyExpression pe = da.getPredicate();
      IRI subjIRI = resolveIArg(da.getFirstArgument(), indBindings);
      if (subjIRI != null) {
        OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
        IRI litVar = unboundVar(da.getSecondArgument(), indBindings, litBindings);
        OWLLiteral alreadyBound = (da.getSecondArgument() instanceof SWRLVariable)
            ? litBindings.get(((SWRLVariable) da.getSecondArgument()).getIRI()) : null;
        List<OWLLiteral> candidates = new ArrayList<>();
        for (OWLOntology ont : ontology.getImportsClosure()) {
          for (OWLDataPropertyAssertionAxiom ax : ont.getDataPropertyAssertionAxioms(subj)) {
            if (ax.getProperty().equals(pe)) candidates.add(ax.getObject());
          }
        }
        if (candidates.isEmpty()) {
          r.note = "no DataPropertyAssertion(" + iriFragment(pe.toString()) + ", "
              + iriFragment(subjIRI.toString()) + ", ?)";
        } else if (alreadyBound != null) {
          r.satisfied = candidates.contains(alreadyBound);
          if (!r.satisfied) r.note = "value " + alreadyBound.getLiteral() + " not asserted";
        } else {
          r.satisfied = true;
          r.candidateCount = candidates.size();
          if (litVar != null) r.newLitBindings.put(litVar, candidates.get(0));
        }
      } else {
        r.skip = true;
        r.note = "subject unbound";
      }
      return r;
    }

    // --- DifferentIndividualsAtom ---
    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      IRI a = resolveIArg(dia.getFirstArgument(), indBindings);
      IRI b = resolveIArg(dia.getSecondArgument(), indBindings);
      if (a != null && b != null) {
        r.satisfied = !a.equals(b); // Unique Name Assumption for SWRL
        if (!r.satisfied) r.note = "both arguments resolve to the same individual";
      } else {
        r.skip = true;
        r.note = "argument(s) unbound";
      }
      return r;
    }

    // --- BuiltInAtom (swrlb:add only for now) ---
    if (atom instanceof SWRLBuiltInAtom) {
      SWRLBuiltInAtom ba = (SWRLBuiltInAtom) atom;
      String fn = iriFragment(ba.getPredicate().toString());
      List<SWRLDArgument> args = ba.getArguments();
      if (fn.equals("add") && args.size() == 3) {
        // swrlb:add(?result, ?a, ?b)
        OWLLiteral v1 = resolveLitArg(args.get(1), litBindings);
        OWLLiteral v2 = resolveLitArg(args.get(2), litBindings);
        if (v1 != null && v2 != null) {
          try {
            double sum = Double.parseDouble(v1.getLiteral()) + Double.parseDouble(v2.getLiteral());
            r.satisfied = true;
            IRI resultVar = unboundVar(args.get(0), indBindings, litBindings);
            if (resultVar != null)
              r.newLitBindings.put(resultVar,
                  df.getOWLLiteral(String.valueOf(sum), df.getDoubleOWLDatatype()));
          } catch (NumberFormatException e) {
            r.note = "arithmetic error: " + v1.getLiteral() + " + " + v2.getLiteral();
          }
        } else {
          r.note = "input(s) not yet bound";
        }
      } else {
        r.skip = true;
        r.note = "builtin '" + fn + "' not evaluated";
      }
      return r;
    }

    // Unknown atom type
    r.skip = true;
    r.note = "unknown atom type";
    return r;
  }

  /** Resolves a SWRL data argument to an OWLLiteral if bound, else null. */
  private static OWLLiteral resolveLitArg(SWRLDArgument arg, Map<IRI, OWLLiteral> litBindings) {
    if (arg instanceof SWRLLiteralArgument) return ((SWRLLiteralArgument) arg).getLiteral();
    if (arg instanceof SWRLVariable) return litBindings.get(((SWRLVariable) arg).getIRI());
    return null;
  }

  /** Result of evaluating a single SWRL body atom. */
  private static class AtomResult {
    boolean satisfied    = false;
    boolean skip         = false;  // cannot evaluate (e.g. unsupported builtin)
    Map<IRI, IRI>        newIndBindings = new LinkedHashMap<>();
    Map<IRI, OWLLiteral> newLitBindings = new LinkedHashMap<>();
    int    candidateCount = 1;
    String note           = "";
  }

  /** Best variable bindings found by {@link #searchPaths} together with their score. */
  private static class PathResult {
    int score = 0;
    Map<IRI, IRI>        indBindings = new LinkedHashMap<>();
    Map<IRI, OWLLiteral> litBindings = new LinkedHashMap<>();
  }

  /** Maximum number of candidates to explore per branching atom during path search. */
  private static final int MAX_BRANCH = 6;

  /** Returns {@code label} if available, otherwise the IRI fragment. */
  private static String labelOrFrag(IRI iri, Map<IRI, String> labels) {
    if (iri == null) return "?";
    String label = labels.get(iri);
    return (label != null) ? label : iriFragment(iri.toString());
  }

  /** Resolves a SWRL individual argument to its bound individual IRI, or null if unbound. */
  private static IRI resolveIArg(SWRLIArgument arg, Map<IRI, IRI> indBindings) {
    if (arg instanceof SWRLIndividualArgument) {
      OWLIndividual ind = ((SWRLIndividualArgument) arg).getIndividual();
      return (ind instanceof OWLNamedIndividual) ? ((OWLNamedIndividual) ind).getIRI() : null;
    }
    if (arg instanceof SWRLVariable)
      return indBindings.get(((SWRLVariable) arg).getIRI());
    return null;
  }

  /** Returns the variable IRI if the argument is an unbound SWRL variable, else null. */
  private static IRI unboundVar(SWRLArgument arg, Map<IRI, IRI> indBindings,
      Map<IRI, OWLLiteral> litBindings) {
    if (!(arg instanceof SWRLVariable)) return null;
    IRI v = ((SWRLVariable) arg).getIRI();
    return (indBindings.containsKey(v) || litBindings.containsKey(v)) ? null : v;
  }

  /** Formats a SWRL individual arg for display, substituting bound values. */
  private static String fmtIArg(SWRLIArgument arg, Map<IRI, IRI> indBindings,
      Map<IRI, String> labels) {
    if (arg instanceof SWRLVariable) {
      IRI v = ((SWRLVariable) arg).getIRI();
      IRI bound = indBindings.get(v);
      return (bound != null) ? labelOrFrag(bound, labels) : "?" + iriFragment(v.toString());
    }
    if (arg instanceof SWRLIndividualArgument) {
      OWLIndividual ind = ((SWRLIndividualArgument) arg).getIndividual();
      if (ind instanceof OWLNamedIndividual)
        return labelOrFrag(((OWLNamedIndividual) ind).getIRI(), labels);
    }
    return arg.toString();
  }

  /** Formats a SWRL data arg for display, substituting bound literal values. */
  private static String fmtDArg(SWRLDArgument arg, Map<IRI, OWLLiteral> litBindings) {
    if (arg instanceof SWRLVariable) {
      IRI v = ((SWRLVariable) arg).getIRI();
      OWLLiteral bound = litBindings.get(v);
      return (bound != null) ? bound.getLiteral() : "?" + iriFragment(v.toString());
    }
    if (arg instanceof SWRLLiteralArgument)
      return ((SWRLLiteralArgument) arg).getLiteral().getLiteral();
    return arg.toString();
  }

  /** Formats a SWRL atom for display with bound variables substituted. */
  private static String formatAtomForDisplay(SWRLAtom atom, Map<IRI, IRI> indBindings,
      Map<IRI, OWLLiteral> litBindings, Map<IRI, String> labels) {
    if (atom instanceof SWRLClassAtom) {
      SWRLClassAtom ca = (SWRLClassAtom) atom;
      IRI predIRI = (ca.getPredicate() instanceof OWLClass)
          ? ((OWLClass) ca.getPredicate()).getIRI() : null;
      return labelOrFrag(predIRI, labels) + "\t(" + fmtIArg(ca.getArgument(), indBindings, labels) + ")";
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      IRI predIRI = (pa.getPredicate() instanceof OWLObjectProperty)
          ? ((OWLObjectProperty) pa.getPredicate()).getIRI() : null;
      return labelOrFrag(predIRI, labels)
          + "\t(" + fmtIArg(pa.getFirstArgument(), indBindings, labels)
          + ", " + fmtIArg(pa.getSecondArgument(), indBindings, labels) + ")";
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      IRI predIRI = (da.getPredicate() instanceof OWLDataProperty)
          ? ((OWLDataProperty) da.getPredicate()).getIRI() : null;
      return labelOrFrag(predIRI, labels)
          + "\t(" + fmtIArg(da.getFirstArgument(), indBindings, labels)
          + ", " + fmtDArg(da.getSecondArgument(), litBindings) + ")";
    }
    if (atom instanceof SWRLBuiltInAtom) {
      SWRLBuiltInAtom ba = (SWRLBuiltInAtom) atom;
      List<String> parts = new ArrayList<>();
      for (SWRLDArgument a : ba.getArguments()) parts.add(fmtDArg(a, litBindings));
      return iriFragment(ba.getPredicate().toString()) + "\t(" + String.join(", ", parts) + ")";
    }
    if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      return "differentFrom\t("
          + fmtIArg(dia.getFirstArgument(), indBindings, labels)
          + ", " + fmtIArg(dia.getSecondArgument(), indBindings, labels) + ")";
    }
    return atom.getClass().getSimpleName();
  }

  /**
   * Prints the variable→value binding table to stdout using the requested format.
   * Individual bindings are shown as IRI fragments; literal bindings as raw values.
   * Used in place of OWL axiom serialization when {@code --constraint} is active.
   */
  private static void outputBindings(Map<IRI, IRI> indBindings,
      Map<IRI, OWLLiteral> litBindings, String format) {
    List<String> header = Arrays.asList("variable", "value");
    char sep = format.equals("csv") ? ',' : '\t';
    if (format.equals("markdown")) {
      System.out.println(mdRow(header));
      System.out.println(mdSep(2));
    } else {
      System.out.println(joinRow(header, sep));
    }
    for (Map.Entry<IRI, IRI> e : indBindings.entrySet()) {
      List<String> row = Arrays.asList(
          "?" + iriFragment(e.getKey().toString()),
          iriFragment(e.getValue().toString()));
      if (format.equals("markdown")) System.out.println(mdRow(row));
      else System.out.println(joinRow(row, sep));
    }
    for (Map.Entry<IRI, OWLLiteral> e : litBindings.entrySet()) {
      List<String> row = Arrays.asList(
          "?" + iriFragment(e.getKey().toString()),
          e.getValue().getLiteral());
      if (format.equals("markdown")) System.out.println(mdRow(row));
      else System.out.println(joinRow(row, sep));
    }
  }

  /** Filters a set of axioms to those whose signature contains at least one of the pivot individuals. */
  private static Set<OWLAxiom> filterByIndividuals(Set<OWLAxiom> axioms,
      Set<OWLNamedIndividual> pivots) {
    Set<OWLAxiom> filtered = new HashSet<>();
    for (OWLAxiom ax : axioms) {
      for (OWLEntity e : ax.getSignature()) {
        if (e instanceof OWLNamedIndividual && pivots.contains(e)) {
          filtered.add(ax);
          break;
        }
      }
    }
    return filtered;
  }

  /**
   * Builds a merged prefix map by collecting prefix declarations from every ontology
   * in the import closure.  Needed so that prefixes declared only in imported files
   * (e.g. "recipe:" from recipes.ofn) are available when resolving constraint IRIs.
   */
  private static Map<String, String> buildMergedPrefixMap(OWLOntologyManager manager,
      OWLOntology ontology) {
    Map<String, String> merged = new HashMap<>();
    for (OWLOntology ont : ontology.getImportsClosure()) {
      OWLDocumentFormat fmt = manager.getOntologyFormat(ont);
      if (fmt != null && fmt.isPrefixOWLOntologyFormat()) {
        merged.putAll(fmt.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap());
      }
    }
    return merged;
  }

  /**
   * Resolves a prefix-qualified token ("prefix:local"), a bracketed full IRI
   * ("<http://...>"), or a bare local name (matched against the default ":" prefix)
   * to an {@link IRI}.  Returns null and is silent if resolution fails — the caller
   * is responsible for printing an error.
   */
  private static IRI resolveIRI(String token, Map<String, String> prefixes) {
    token = token.trim();
    if (token.isEmpty()) return null;
    if (token.startsWith("<") && token.endsWith(">"))
      return IRI.create(token.substring(1, token.length() - 1));
    int colon = token.indexOf(':');
    if (colon > 0) {
      String prefixKey = token.substring(0, colon + 1); // e.g. "obo:"
      String local     = token.substring(colon + 1);
      String ns = prefixes.get(prefixKey);
      if (ns != null) return IRI.create(ns + local);
    }
    // Bare name — try the default prefix (empty-string alias ":")
    String defaultNs = prefixes.get(":");
    if (defaultNs != null && !token.contains(":"))
      return IRI.create(defaultNs + token);
    return null;
  }

  /**
   * Returns the predicate IRI for a SWRL body atom, or null for built-in atoms,
   * DifferentIndividuals atoms, and other non-predicate forms.
   */
  private static IRI atomPredicateIRI(SWRLAtom atom) {
    if (atom instanceof SWRLClassAtom) {
      OWLClassExpression ce = ((SWRLClassAtom) atom).getPredicate();
      return (ce instanceof OWLClass) ? ((OWLClass) ce).getIRI() : null;
    }
    if (atom instanceof SWRLObjectPropertyAtom) {
      OWLObjectPropertyExpression pe = ((SWRLObjectPropertyAtom) atom).getPredicate();
      return (pe instanceof OWLObjectProperty) ? ((OWLObjectProperty) pe).getIRI() : null;
    }
    if (atom instanceof SWRLDataPropertyAtom) {
      OWLDataPropertyExpression pe = ((SWRLDataPropertyAtom) atom).getPredicate();
      return (pe instanceof OWLDataProperty) ? ((OWLDataProperty) pe).getIRI() : null;
    }
    return null;
  }

  /**
   * Parses each raw constraint string of the form {@code predicate(arg)} or
   * {@code predicate(subject, object)}, finds the matching body atom in the rule,
   * and resolves each argument to a SWRL variable binding.
   *
   * <p>Results are printed to stderr.  The returned map is keyed by SWRL variable IRI
   * (e.g. {@code urn:swrl:var#p_combination}) and valued by individual IRI.
   */
  private static Map<IRI, IRI> resolveConstraintBindings(List<String> rawConstraints,
      SWRLAPIRule rule, Map<String, String> prefixes, Map<IRI, String> labels,
      Set<SWRLAtom> matchedAtoms, boolean warnMissing) {

    Map<IRI, IRI> bindings = new LinkedHashMap<>();
    List<SWRLAtom> bodyAtoms = new ArrayList<>(rule.getBody());

    for (String raw : rawConstraints) {
      int parenOpen  = raw.indexOf('(');
      int parenClose = raw.lastIndexOf(')');
      if (parenOpen < 1 || parenClose <= parenOpen) {
        System.err.println("# WARNING: the --constraint argument \"" + raw
            + "\" is malformed. Expected a format of predicate name followed by an instance"
            + " identifier or two in parentheses, e.g. \"predicate(id)\" or \"predicate(id, id)\"");
        continue;
      }
      String   predicatePart = raw.substring(0, parenOpen).trim();
      String[] argTokens     = raw.substring(parenOpen + 1, parenClose).split(",", -1);

      IRI predicateIRI = resolveIRI(predicatePart, prefixes);
      if (predicateIRI == null) {
        System.err.println("# ERROR: cannot resolve predicate '" + predicatePart
            + "' — check prefix declarations");
        continue;
      }

      // Find the first body atom whose predicate matches
      SWRLAtom matchedAtom = null;
      for (SWRLAtom candidate : bodyAtoms) {
        if (predicateIRI.equals(atomPredicateIRI(candidate))) {
          matchedAtom = candidate;
          break;
        }
      }
      if (matchedAtom == null) {
        if (warnMissing) {
          System.err.println("# ERROR: no body atom with predicate '"
              + iriFragment(predicateIRI.toString()) + "' found in rule '"
              + rule.getRuleName() + "'");
        }
        continue;
      }
      matchedAtoms.add(matchedAtom);

      // Bind the atom's variable argument(s) to the supplied individual IRI(s)
      if (matchedAtom instanceof SWRLClassAtom) {
        bindConstraintVariable(((SWRLClassAtom) matchedAtom).getArgument(),
            argTokens, 0, prefixes, bindings);
      } else if (matchedAtom instanceof SWRLObjectPropertyAtom) {
        SWRLObjectPropertyAtom pAtom = (SWRLObjectPropertyAtom) matchedAtom;
        bindConstraintVariable(pAtom.getFirstArgument(),  argTokens, 0, prefixes, bindings);
        bindConstraintVariable(pAtom.getSecondArgument(), argTokens, 1, prefixes, bindings);
      } else if (matchedAtom instanceof SWRLDataPropertyAtom) {
        bindConstraintVariable(((SWRLDataPropertyAtom) matchedAtom).getFirstArgument(),
            argTokens, 0, prefixes, bindings);
      }
    }

    return bindings;
  }

  /**
   * If {@code arg} is a SWRL variable and {@code argTokens[argIdx]} resolves to an IRI,
   * records the binding in {@code bindings} and prints it on stderr.
   */
  private static void bindConstraintVariable(SWRLArgument arg, String[] argTokens, int argIdx,
      Map<String, String> prefixes, Map<IRI, IRI> bindings) {
    if (!(arg instanceof SWRLVariable)) return;
    if (argIdx >= argTokens.length) return;
    IRI varIRI = ((SWRLVariable) arg).getIRI();
    IRI indIRI = resolveIRI(argTokens[argIdx].trim(), prefixes);
    if (indIRI == null) {
      System.err.println("# ERROR: cannot resolve argument '" + argTokens[argIdx].trim()
          + "' — check prefix declarations");
      return;
    }
    bindings.put(varIRI, indIRI);
  }

  /**
   * Filters inferred axioms to those that involve at least one "pivot" individual —
   * an individual that participates as subject in the first body atom of the rule.
   * Prints debug information on stderr showing which atom was used and how many
   * axioms were retained.
   */
  private static Set<OWLAxiom> debugFilterAxioms(Set<OWLAxiom> inferred,
      SWRLAPIRule rule, OWLOntology ontology) {
    Set<SWRLAtom> body = rule.getBody();
    if (body.isEmpty()) {
      System.err.println("# Debug: rule has no body atoms; no filtering applied");
      return inferred;
    }
    SWRLAtom firstAtom = body.iterator().next();
    Set<OWLNamedIndividual> pivots = extractPivotIndividuals(firstAtom, ontology);

    if (pivots.isEmpty()) {
      System.err.println("# Debug: first body atom (" + describeAtom(firstAtom)
          + ") matched no explicit individuals — rule likely did not fire");
      return new HashSet<>();
    }
    System.err.println("# Debug: pivot on first body atom (" + describeAtom(firstAtom)
        + ") — " + pivots.size() + " matching individual(s)");

    Set<OWLAxiom> filtered = new HashSet<>();
    for (OWLAxiom ax : inferred) {
      for (OWLEntity e : ax.getSignature()) {
        if (e instanceof OWLNamedIndividual && pivots.contains(e)) {
          filtered.add(ax);
          break;
        }
      }
    }
    System.err.println("# Debug: " + filtered.size() + " of " + inferred.size()
        + " inferred axiom(s) retained after filter");
    return filtered;
  }

  /**
   * Collects named individuals that are subjects of the given SWRL body atom
   * across the full import closure. Handles class atoms, object property atoms,
   * and data property atoms; returns an empty set for built-ins and others.
   */
  private static Set<OWLNamedIndividual> extractPivotIndividuals(SWRLAtom atom,
      OWLOntology ontology) {
    Set<OWLNamedIndividual> result = new HashSet<>();
    for (OWLOntology ont : ontology.getImportsClosure()) {
      if (atom instanceof SWRLClassAtom) {
        OWLClassExpression ce = ((SWRLClassAtom) atom).getPredicate();
        for (OWLClassAssertionAxiom ax : ont.getAxioms(AxiomType.CLASS_ASSERTION)) {
          if (ax.getClassExpression().equals(ce)
              && ax.getIndividual() instanceof OWLNamedIndividual)
            result.add((OWLNamedIndividual) ax.getIndividual());
        }
      } else if (atom instanceof SWRLObjectPropertyAtom) {
        OWLObjectPropertyExpression pe = ((SWRLObjectPropertyAtom) atom).getPredicate();
        for (OWLObjectPropertyAssertionAxiom ax :
            ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION)) {
          if (ax.getProperty().equals(pe)
              && ax.getSubject() instanceof OWLNamedIndividual)
            result.add((OWLNamedIndividual) ax.getSubject());
        }
      } else if (atom instanceof SWRLDataPropertyAtom) {
        OWLDataPropertyExpression pe = ((SWRLDataPropertyAtom) atom).getPredicate();
        for (OWLDataPropertyAssertionAxiom ax :
            ont.getAxioms(AxiomType.DATA_PROPERTY_ASSERTION)) {
          if (ax.getProperty().equals(pe)
              && ax.getSubject() instanceof OWLNamedIndividual)
            result.add((OWLNamedIndividual) ax.getSubject());
        }
      }
      // SWRLBuiltInAtom, SWRLDifferentIndividualsAtom, etc.: no pivot; caller skips filtering
    }
    return result;
  }

  /**
   * Returns the SWRL variable names (e.g. {@code ["?p_combination", "?pcm_1"]}) for every
   * {@link SWRLVariable} argument in the atom, in argument order.
   * Non-variable arguments (named individuals, literals) are omitted.
   */
  private static List<String> getAtomVarNames(SWRLAtom atom) {
    List<String> names = new ArrayList<>();
    if (atom instanceof SWRLClassAtom) {
      addIfVar(names, ((SWRLClassAtom) atom).getArgument());
    } else if (atom instanceof SWRLObjectPropertyAtom) {
      SWRLObjectPropertyAtom pa = (SWRLObjectPropertyAtom) atom;
      addIfVar(names, pa.getFirstArgument());
      addIfVar(names, pa.getSecondArgument());
    } else if (atom instanceof SWRLDataPropertyAtom) {
      SWRLDataPropertyAtom da = (SWRLDataPropertyAtom) atom;
      addIfVar(names, da.getFirstArgument());
      addIfVar(names, da.getSecondArgument());
    } else if (atom instanceof SWRLBuiltInAtom) {
      for (SWRLDArgument a : ((SWRLBuiltInAtom) atom).getArguments()) addIfVar(names, a);
    } else if (atom instanceof SWRLDifferentIndividualsAtom) {
      SWRLDifferentIndividualsAtom dia = (SWRLDifferentIndividualsAtom) atom;
      addIfVar(names, dia.getFirstArgument());
      addIfVar(names, dia.getSecondArgument());
    }
    return names;
  }

  private static void addIfVar(List<String> names, SWRLArgument arg) {
    if (arg instanceof SWRLVariable)
      names.add("?" + iriFragment(((SWRLVariable) arg).getIRI().toString()));
  }

  /** Returns a short description of a SWRL atom for debug output, using IRI fragment only. */
  private static String describeAtom(SWRLAtom atom) {
    if (atom instanceof SWRLClassAtom)
      return "ClassAtom(" + iriFragment(((SWRLClassAtom) atom).getPredicate().toString()) + ")";
    if (atom instanceof SWRLObjectPropertyAtom)
      return "ObjectPropertyAtom("
          + iriFragment(((SWRLObjectPropertyAtom) atom).getPredicate().toString()) + ")";
    if (atom instanceof SWRLDataPropertyAtom)
      return "DataPropertyAtom("
          + iriFragment(((SWRLDataPropertyAtom) atom).getPredicate().toString()) + ")";
    if (atom instanceof SWRLBuiltInAtom)
      return "BuiltInAtom(" + iriFragment(((SWRLBuiltInAtom) atom).getPredicate().toString()) + ")";
    return atom.getClass().getSimpleName();
  }

  /** Extracts the fragment or last path segment from an IRI string for compact display. */
  private static String iriFragment(String iri) {
    // Strip angle brackets if present (OWLAPI sometimes includes them)
    iri = iri.replaceAll("^<|>$", "");
    int hash = iri.lastIndexOf('#');
    if (hash >= 0) return iri.substring(hash + 1);
    int slash = iri.lastIndexOf('/');
    if (slash >= 0) return iri.substring(slash + 1);
    return iri;
  }

  /**
   * Builds an index of inverse object-property relationships from the import closure.
   * For every {@code InverseObjectProperties(p, q)} axiom, both {@code p→q} and
   * {@code q→p} entries are recorded so lookups work in either direction.
   */
  private static Map<IRI, Set<IRI>> buildInversePropertyMap(OWLOntology ontology) {
    Map<IRI, Set<IRI>> inverses = new HashMap<>();
    for (OWLOntology ont : ontology.getImportsClosure()) {
      for (OWLInverseObjectPropertiesAxiom ax :
          ont.getAxioms(AxiomType.INVERSE_OBJECT_PROPERTIES)) {
        OWLObjectPropertyExpression first  = ax.getFirstProperty();
        OWLObjectPropertyExpression second = ax.getSecondProperty();
        if (first instanceof OWLObjectProperty && second instanceof OWLObjectProperty) {
          IRI a = ((OWLObjectProperty) first).getIRI();
          IRI b = ((OWLObjectProperty) second).getIRI();
          inverses.computeIfAbsent(a, k -> new HashSet<>()).add(b);
          inverses.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }
      }
    }
    return inverses;
  }

  /**
   * Returns {@code true} if any inverse of the atom's predicate has an assertion
   * matching the same pair of individuals in swapped order, indicating that the
   * ontology expresses the relationship via the inverse property rather than directly.
   *
   * <ul>
   *   <li>Both bound — checks for {@code inv(obj, subj)}.</li>
   *   <li>Subject bound, object unbound — checks for any {@code inv(?, subj)}.</li>
   *   <li>Object bound, subject unbound — checks for any {@code inv(obj, ?)}.</li>
   * </ul>
   */
  private static boolean checkInverseOnly(SWRLObjectPropertyAtom atom,
      Map<IRI, IRI> indBindings, Map<IRI, Set<IRI>> inverseMap,
      OWLOntology ontology, OWLDataFactory df) {
    OWLObjectPropertyExpression pe = atom.getPredicate();
    if (!(pe instanceof OWLObjectProperty)) return false;
    IRI predIRI = ((OWLObjectProperty) pe).getIRI();
    Set<IRI> invIRIs = inverseMap.get(predIRI);
    if (invIRIs == null || invIRIs.isEmpty()) return false;

    IRI subjIRI = resolveIArg(atom.getFirstArgument(),  indBindings);
    IRI objIRI  = resolveIArg(atom.getSecondArgument(), indBindings);

    for (IRI invIRI : invIRIs) {
      OWLObjectProperty invProp = df.getOWLObjectProperty(invIRI);
      for (OWLOntology ont : ontology.getImportsClosure()) {
        if (subjIRI != null && objIRI != null) {
          // pred(subj, obj) failed — check inv(obj, subj)
          OWLNamedIndividual obj  = df.getOWLNamedIndividual(objIRI);
          OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
          for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(obj))
            if (ax.getProperty().equals(invProp) && ax.getObject().equals(subj)) return true;
        } else if (subjIRI != null) {
          // pred(subj, ?y) failed — check inv(?, subj): subj appears as object
          OWLNamedIndividual subj = df.getOWLNamedIndividual(subjIRI);
          for (OWLObjectPropertyAssertionAxiom ax :
              ont.getAxioms(AxiomType.OBJECT_PROPERTY_ASSERTION))
            if (ax.getProperty().equals(invProp) && ax.getObject().equals(subj)) return true;
        } else if (objIRI != null) {
          // pred(?x, obj) failed — check inv(obj, ?): obj appears as subject
          OWLNamedIndividual obj = df.getOWLNamedIndividual(objIRI);
          for (OWLObjectPropertyAssertionAxiom ax : ont.getObjectPropertyAssertionAxioms(obj))
            if (ax.getProperty().equals(invProp)) return true;
        }
      }
    }
    return false;
  }

  /**
   * Builds a map from entity IRI to rdfs:label string, searching the full imports
   * closure.  English-tagged and untagged literals take precedence over other languages.
   */
  private static Map<IRI, String> buildLabelMap(OWLOntology ontology) {
    OWLAnnotationProperty rdfsLabel =
        ontology.getOWLOntologyManager().getOWLDataFactory().getRDFSLabel();
    Map<IRI, String> labels = new HashMap<>();
    for (OWLOntology ont : ontology.getImportsClosure()) {
      for (OWLAnnotationAssertionAxiom ax : ont.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
        if (!ax.getProperty().equals(rdfsLabel)) continue;
        if (!(ax.getSubject() instanceof IRI)) continue;
        if (!(ax.getValue() instanceof OWLLiteral)) continue;
        IRI iri = (IRI) ax.getSubject();
        OWLLiteral lit = (OWLLiteral) ax.getValue();
        String lang = lit.getLang();
        if (lang.isEmpty() || lang.equals("en")) {
          labels.put(iri, lit.getLiteral().trim());      // preferred: English / untagged
        } else {
          labels.putIfAbsent(iri, lit.getLiteral().trim()); // fallback: other languages
        }
      }
    }
    return labels;
  }

  /**
   * Returns the prefix-name → namespace-IRI map from the ontology's document format,
   * or an empty map if the format has no prefix declarations.
   */
  private static Map<String, String> buildPrefixMap(OWLOntologyManager manager,
      OWLOntology ontology) {
    OWLDocumentFormat fmt = manager.getOntologyFormat(ontology);
    if (fmt != null && fmt.isPrefixOWLOntologyFormat()) {
      return new HashMap<>(fmt.asPrefixOWLOntologyFormat().getPrefixName2PrefixMap());
    }
    return new HashMap<>();
  }

  /**
   * Replaces each {@code prefix:localName} token in a rendered SWRL rule string with
   * {@code 'rdfs:label'} when the ontology (and its imports) contains that label.
   * Tokens with no label are left as-is.
   */
  private static String humanizeRule(String rendered, Map<IRI, String> labels,
      Map<String, String> prefixes) {
    if (prefixes.isEmpty()) return rendered;
    Pattern p = Pattern.compile("([a-zA-Z_][\\w]*):(\\w+)");
    Matcher m = p.matcher(rendered);
    StringBuffer sb = new StringBuffer();
    while (m.find()) {
      String nsIRI = prefixes.get(m.group(1) + ":");
      String replacement = m.group(0);
      if (nsIRI != null) {
        String label = labels.get(IRI.create(nsIRI + m.group(2)));
        if (label != null) {
          replacement = label.contains(" ")
              ? "'" + label.replace("'", "\u2019") + "'"
              : label;
        }
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  /** Formats the ontology summary header line (no axiom count). */
  private static String ontologyHeader(String name, String catalogNote, String format) {
    String prefix = format.equals("markdown") ? "## " : "# ";
    return prefix + "Ontology: " + name + (catalogNote.isEmpty() ? "" : " (" + catalogNote + ")");
  }

  /** Formats the ontology summary header line including an axiom count. */
  private static String ontologyHeader(String name, String catalogNote, int axioms, String format) {
    String prefix = format.equals("markdown") ? "## " : "# ";
    String detail = axioms + " axiom(s)" + (catalogNote.isEmpty() ? "" : ", " + catalogNote);
    return prefix + "Ontology: " + name + " (" + detail + ")";
  }

  /** Colour and status legend printed once per debug report, below the ontology header. */
  private static String debugLegend(String format, SwrltabConfig config,
      Map<String, String> resolvedLabels, Map<String, String> resolvedBare) {
    boolean md = format.equals("markdown");
    StringBuilder sb = new StringBuilder();
    if (md) {
      String colorLegend = buildColorLegend(config);
      if (!colorLegend.isEmpty()) sb.append(colorLegend).append("  \n");
      sb.append("**PASS** = matching assertion found in ontology  \n");
      sb.append("**FAIL** = no matching assertion found (predicate looked up, specific fact absent)  \n");
      sb.append("**SKIP** = not evaluated (required variable(s) unbound)");
      if (!resolvedLabels.isEmpty()) {
        sb.append("  \n**Quoted labels:** ");
        List<String> links = new ArrayList<>();
        resolvedLabels.forEach((label, iri) -> links.add("[" + label + "](" + iri + ")"));
        sb.append(String.join(" &nbsp;·&nbsp; ", links));
      }
      if (!resolvedBare.isEmpty()) {
        sb.append("  \n**Bare predicates:** ");
        List<String> links = new ArrayList<>();
        resolvedBare.forEach((label, iri) -> links.add("[" + label + "](" + iri + ")"));
        sb.append(String.join(" &nbsp;·&nbsp; ", links));
      }
    } else {
      sb.append("PASS = matching assertion found in ontology\n");
      sb.append("FAIL = no matching assertion found (predicate looked up, specific fact absent)\n");
      sb.append("SKIP = not evaluated (required variable(s) unbound)");
    }
    return sb.toString();
  }

  /** Escapes characters that would break a markdown table cell. */
  private static String escapeMd(String s) {
    return s.replace("|", "\\|").replace("\n", " ").replace("\r", "");
  }

  /** Formats a list of cells as a markdown table row. */
  private static String mdRow(List<String> cells) {
    StringBuilder sb = new StringBuilder("|");
    for (String c : cells) sb.append(' ').append(escapeMd(c)).append(" |");
    return sb.toString();
  }

  /** Produces the markdown table separator line for the given number of columns. */
  private static String mdSep(int cols) {
    StringBuilder sb = new StringBuilder("|");
    for (int j = 0; j < cols; j++) sb.append("---|");
    return sb.toString();
  }

  /**
   * Parses an OASIS XML catalog file (catalog-v001.xml as generated by Protégé) and
   * registers {@link SimpleIRIMapper} entries on the manager for every {@code <uri>}
   * element whose local target file exists.  Only local mappings are applied; entries
   * whose target file is absent are silently skipped so that remote-only imports do
   * not cause a network fetch.
   */
  private static int applyCatalog(OWLOntologyManager manager, File catalogFile) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setNamespaceAware(true);
      Document doc = dbf.newDocumentBuilder().parse(catalogFile);
      // Catalog elements live in the OASIS catalog namespace; also handle documents
      // that omit the namespace (some editors produce these).
      NodeList uris = doc.getElementsByTagNameNS(
          "urn:oasis:names:tc:entity:xmlns:xml:catalog", "uri");
      if (uris.getLength() == 0) uris = doc.getElementsByTagName("uri");
      int mapped = 0;
      for (int j = 0; j < uris.getLength(); j++) {
        Element el = (Element) uris.item(j);
        String name = el.getAttribute("name");
        String uri = el.getAttribute("uri");
        // Skip blank entries and Protégé's "duplicate:" pseudo-IRIs.
        if (name.isEmpty() || uri.isEmpty() || name.startsWith("duplicate:")) continue;
        File target = new File(catalogFile.getParentFile(), uri);
        if (target.exists()) {
          manager.addIRIMapper(new SimpleIRIMapper(IRI.create(name), IRI.create(target.toURI())));
          mapped++;
        }
      }
      return mapped;
    } catch (Exception e) {
      System.err.println("Warning: could not parse catalog " + catalogFile + ": " + e.getMessage());
      return 0;
    }
  }

  private static void usage(String msg) {
    if (msg != null) System.err.println("Error: " + msg);
    System.err.println("Usage: SWRLTabCLI [options] <ontology.owl>");
    System.err.println();
    System.err.println("Query modes (run exactly one):");
    System.err.println("  --query <name>          Run named SQWRL query already in the ontology");
    System.err.println("  --query-text <sqwrl>    Run an inline SQWRL expression");
    System.err.println("    --query-name <name>     Name to assign to the result set (default: cli-query)");
    System.err.println("  --infer                 Fire all SWRL rules; print inferred axioms as OWL Functional Syntax");
    System.err.println("  --rule <name>           Fire a single named SWRL rule; print inferred axioms as OWL Functional Syntax");
    System.err.println("  --list-queries          Print SQWRL queries stored in the ontology");
    System.err.println("  --list-rules            Print SWRL rules stored in the ontology");
    System.err.println();
    System.err.println("Output options:");
    System.err.println("  --format tsv|csv|markdown  Output format (default: tsv)");
    System.err.println("                             markdown: table with rdfs:label substitution for --list-rules/--list-queries");
    System.err.println("  --ignore-imports        Silently skip unresolvable owl:imports declarations");
    System.err.println("  --debug                 With --rule: filter inferred axioms to those involving");
    System.err.println("                          individuals matched by the first antecedent term");
    System.err.println("  --constraint <atom>     Bind a variable for --rule debug evaluation.");
    System.err.println("                          Format: Class(individual) or property(subject,object)");
    System.err.println("                          Use prefix-qualified names (e.g. obo:DEMO_00011(recipe:r1.s2.x))");
    System.err.println("                          Implies --debug. May be repeated.");
    System.exit(msg == null ? 0 : 1);
  }
}
