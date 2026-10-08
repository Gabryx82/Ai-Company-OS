package com.aicompany.backend.blueprint.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.api.Versioned;
import com.aicompany.backend.blueprint.exception.BlueprintEdgeNotFoundException;
import com.aicompany.backend.blueprint.exception.ChangeProposalNotFoundException;
import com.aicompany.backend.blueprint.exception.ProposalDecidedException;
import com.aicompany.backend.blueprint.model.AiAction;
import com.aicompany.backend.blueprint.model.BlueprintEdge;
import com.aicompany.backend.blueprint.model.BlueprintNode;
import com.aicompany.backend.blueprint.model.BlueprintVocabulary;
import com.aicompany.backend.blueprint.model.ChangeProposal;
import com.aicompany.backend.blueprint.model.CodeStructure;
import com.aicompany.backend.blueprint.model.EdgeOrigin;
import com.aicompany.backend.blueprint.model.NodeKind;
import com.aicompany.backend.blueprint.model.PathState;
import com.aicompany.backend.blueprint.model.ProposalKind;
import com.aicompany.backend.blueprint.model.ProposalStatus;
import com.aicompany.backend.blueprint.model.RelationType;
import com.aicompany.backend.blueprint.repository.BlueprintEdgeRepository;
import com.aicompany.backend.blueprint.repository.ChangeProposalRepository;
import com.aicompany.backend.task.dto.TaskResponse;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.service.TaskService;
import com.aicompany.backend.workspace.service.ProjectWorkspaceService;
import com.aicompany.backend.workspace.service.WorkspacePaths;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Proposed Code Changes and AI actions (ADR-033 §7-8): the two ways the Blueprint
 * asks for work on the code, and both end in a task. Nothing here writes a source
 * file -- a diff is text for a person to read, and accepting it hands it to the
 * governance workflow that already exists: a task, an agent or a handoff, a review.
 */
@Service
@Transactional
public class ChangeProposalService {

    public record ProposalDraft(ProposalKind kind, Long nodeId, Long edgeId, String filePath, String title,
                                String reason, String consequences, String diff) {
    }

    /** What fits in a task's description (V1: 5000); a longer diff goes to a file the task names. */
    static final int TASK_DESCRIPTION = 5000;

    private final ChangeProposalRepository proposals;
    private final BlueprintEdgeRepository edges;
    private final BlueprintService blueprint;
    private final ProjectWorkspaceService workspace;
    private final TaskService tasks;
    private final BlueprintAiOutcomes outcomes;

    public ChangeProposalService(ChangeProposalRepository proposals, BlueprintEdgeRepository edges,
                                 BlueprintService blueprint, ProjectWorkspaceService workspace, TaskService tasks,
                                 BlueprintAiOutcomes outcomes) {
        this.outcomes = outcomes;
        this.proposals = proposals;
        this.edges = edges;
        this.blueprint = blueprint;
        this.workspace = workspace;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public List<BlueprintViews.BlueprintProposal> list(Long projectId) {
        blueprint.requireProject(projectId);
        return proposals.findAllByProjectIdOrderByIdDesc(projectId).stream().map(ChangeProposalService::view).toList();
    }

    /**
     * A proposal. CREATE_FILE and ADD_DEPENDENCY are generated from the Blueprint
     * and the real file; CUSTOM is written by the operator. None is applied.
     */
    public BlueprintViews.BlueprintProposal propose(Long projectId, ProposalDraft draft, String by) {
        blueprint.requireWritableProject(projectId);
        if (draft.kind() == null) {
            throw new RequestValidationException("kind", "is required");
        }
        ChangeProposal proposal = switch (draft.kind()) {
            case CREATE_FILE -> createFile(projectId, draft, by);
            case ADD_DEPENDENCY -> addDependency(projectId, draft, by);
            case CUSTOM -> custom(projectId, draft, by);
        };
        return view(proposals.saveAndFlush(proposal));
    }

    /** Accepting creates the task that carries the change; the diff is not applied here. */
    public BlueprintViews.BlueprintProposal accept(Long projectId, Long proposalId, Precondition precondition, String by) {
        blueprint.requireWritableProject(projectId);
        ChangeProposal proposal = decidable(projectId, proposalId, precondition);
        String header = "Proposed Code Change #" + proposal.getId() + " dal Blueprint, accettata da " + by + ".\n\n"
                + "File: `" + proposal.getFilePath() + "`\n\n"
                + (proposal.getReason() == null ? "" : "Motivo: " + proposal.getReason() + "\n\n")
                + (proposal.getConsequences() == null ? "" : "Conseguenze: " + proposal.getConsequences() + "\n\n")
                + "Applica la modifica seguente, verifica che compili e che i test passino, poi chiedi la review.\n\n";
        String diffBlock = "```diff\n" + proposal.getDiff() + "```\n";
        String description;
        if (header.length() + diffBlock.length() <= TASK_DESCRIPTION) {
            description = header + diffBlock;
        } else {
            String file = ".aicos/proposals/PROPOSAL-" + proposal.getId() + ".diff.md";
            try {
                workspace.write(projectId, file, "# " + proposal.getTitle() + "\n\n" + header + diffBlock);
                description = header + "Il diff completo è in `" + file + "`.";
            } catch (RuntimeException noFolder) {
                description = header + BlueprintService.cut(diffBlock, TASK_DESCRIPTION - header.length() - 40)
                        + "\n(diff troncato)";
            }
        }
        Versioned<TaskResponse> task = tasks.create(BlueprintService.cut("Modifica al codice: " + proposal.getTitle(), 255),
                BlueprintService.cut(description, TASK_DESCRIPTION), TaskStatus.OPEN, TaskPriority.MEDIUM, projectId, null);
        proposal.decide(ProposalStatus.ACCEPTED, by, task.body().id());
        proposals.flush();
        return view(proposal);
    }

    public BlueprintViews.BlueprintProposal reject(Long projectId, Long proposalId, Precondition precondition, String by) {
        blueprint.requireWritableProject(projectId);
        ChangeProposal proposal = decidable(projectId, proposalId, precondition);
        proposal.decide(ProposalStatus.REJECTED, by, null);
        proposals.flush();
        return view(proposal);
    }

    /**
     * An AI action on a block (ADR-033 §8): a task in the project with the
     * component's context and the request. The orchestrator routes it, the cost
     * governance prices it, a person reviews it -- this call only asks.
     */
    public BlueprintViews.BlueprintActionOutcome requestAiAction(Long projectId, Long nodeId, AiAction action, String by) {
        blueprint.requireWritableProject(projectId);
        if (action == null) {
            throw new RequestValidationException("action", "is required");
        }
        BlueprintViews.BlueprintBlockDetail node = blueprint.node(projectId, nodeId);
        BlueprintVocabulary.BlueprintActionInfo info = BlueprintVocabulary.of(action);
        StringBuilder d = new StringBuilder();
        d.append("Richiesta dal Blueprint (").append(info.label()).append("), di ").append(by).append(".\n\n");
        d.append("Componente: **").append(node.name()).append("** — ").append(BlueprintVocabulary.of(node.kind()).label());
        if (node.filePath() != null) {
            d.append(", `").append(node.filePath()).append('`');
            if (node.startLine() != null) d.append(" righe ").append(node.startLine()).append('–').append(node.endLine());
        }
        d.append("\n\n");
        if (node.description() != null) d.append("Descrizione attuale: ").append(node.description()).append("\n\n");
        if (!node.outgoing().isEmpty()) {
            d.append("Usa: ").append(String.join(", ", node.outgoing().stream().limit(12)
                    .map(c -> BlueprintVocabulary.of(c.relation()).label() + " " + c.nodeName()).toList())).append("\n");
        }
        if (!node.incoming().isEmpty()) {
            d.append("È usato da: ").append(String.join(", ", node.incoming().stream().limit(12)
                    .map(c -> c.nodeName() + " (" + BlueprintVocabulary.of(c.relation()).label() + ")").toList())).append("\n");
        }
        d.append("\nContesto: `.aicos/BLUEPRINT.md` (mappa dei componenti), se presente.\n\n");
        d.append("Richiesta: ").append(instruction(action)).append('\n');
        if (action != AiAction.CREATE_IMPLEMENTATION_TASK) {
            d.append("\nNon modificare il codice: il risultato è testo per l'operatore. Una modifica al codice si propone ")
                    .append("come Proposed Code Change nel Blueprint.\n");
        }
        String title = BlueprintService.cut(info.label() + ": " + node.name(), 255);
        Versioned<TaskResponse> task = tasks.create(title, BlueprintService.cut(d.toString(), TASK_DESCRIPTION),
                TaskStatus.OPEN, action == AiAction.CREATE_IMPLEMENTATION_TASK ? TaskPriority.MEDIUM : TaskPriority.LOW,
                projectId, null);
        // PHASE 32 (TD-63): the link that brings the outcome back to this block.
        outcomes.record(projectId, nodeId, task.body().id(), action, by);
        return new BlueprintViews.BlueprintActionOutcome(task.body().id(), task.body().title(), action.name());
    }

    private static String instruction(AiAction action) {
        return switch (action) {
            case EXPLAIN_COMPONENT -> "spiega che cosa fa il componente, perché esiste e come si usa, in italiano, per chi non lo conosce.";
            case GENERATE_PSEUDOCODE -> "scrivi uno pseudocodice sintetico e fedele del componente (IF, FOR EACH, CALL, RETURN, REJECT).";
            case DETECT_MISSING_RELATIONS -> "elenca le dipendenze reali del componente che il Blueprint non mostra, con il punto del codice che le prova.";
            case EXPLAIN_DEPENDENCY -> "spiega perché il componente dipende da ciascuno dei componenti che usa, e se qualche dipendenza è evitabile.";
            case SUGGEST_REFACTOR -> "proponi refactoring motivati, con costi e rischi, senza applicarli.";
            case FIND_ARCHITECTURAL_ISSUE -> "cerca problemi architetturali: accoppiamenti, cicli, responsabilità confuse, violazioni di layer.";
            case GENERATE_DOCUMENTATION -> "scrivi la documentazione del componente in Markdown: scopo, API, dipendenze, esempi.";
            case CREATE_IMPLEMENTATION_TASK -> "realizza o completa il componente come descritto, con i test, rispettando le ADR del progetto.";
        };
    }

    // --- generated proposals ---------------------------------------------------

    private ChangeProposal createFile(Long projectId, ProposalDraft draft, String by) {
        if (draft.nodeId() == null) {
            throw new RequestValidationException("nodeId", "a file to create belongs to a block");
        }
        BlueprintNode node = blueprint.requireNode(projectId, draft.nodeId());
        if (node.getPathState() != PathState.TO_CREATE || node.getFilePath() == null) {
            throw new RequestValidationException("nodeId", "only a block whose file is TO_CREATE can propose creating it");
        }
        String content = skeleton(node);
        return new ChangeProposal(projectId, node.getId(), null, ProposalKind.CREATE_FILE, node.getFilePath(),
                BlueprintService.cut(Optional.ofNullable(BlueprintService.blankToNull(draft.title()))
                        .orElse("Crea " + node.getFilePath()), 255),
                BlueprintService.cut(Optional.ofNullable(BlueprintService.blankToNull(draft.reason()))
                        .orElse("Il blocco «" + node.getName() + "» è progettato nel Blueprint e il suo file non esiste."), 2000),
                BlueprintService.cut(Optional.ofNullable(BlueprintService.blankToNull(draft.consequences()))
                        .orElse("Un file nuovo, nessun file esistente toccato. Il blocco diventerà LINKED alla prossima scansione."), 2000),
                UnifiedDiff.creation(node.getFilePath(), content), by);
    }

    /**
     * USES/CALLS drawn by hand between two Java classes: the field, the import and
     * the constructor parameter the code would need, as a diff of the real file.
     * The file is parsed to find where; the edits keep every other line byte for byte.
     */
    private ChangeProposal addDependency(Long projectId, ProposalDraft draft, String by) {
        if (draft.edgeId() == null) {
            throw new RequestValidationException("edgeId", "a dependency to add is a relation of the Blueprint");
        }
        BlueprintEdge edge = edges.findByIdAndProjectId(draft.edgeId(), projectId)
                .orElseThrow(() -> new BlueprintEdgeNotFoundException(projectId, draft.edgeId()));
        if (edge.getOrigin() == EdgeOrigin.DETECTED) {
            throw new RequestValidationException("edgeId", "the code already has this relation");
        }
        if (edge.getRelation() != RelationType.USES && edge.getRelation() != RelationType.CALLS
                && edge.getRelation() != RelationType.SERVICE_TO_REPOSITORY) {
            throw new RequestValidationException("edgeId", "a dependency is a USES, CALLS or SERVICE_TO_REPOSITORY relation");
        }
        BlueprintNode source = blueprint.requireNode(projectId, edge.getSourceNodeId());
        BlueprintNode target = blueprint.requireNode(projectId, edge.getTargetNodeId());
        if (!"java".equals(source.getLanguage()) || source.getPathState() != PathState.LINKED || source.getFilePath() == null) {
            throw new RequestValidationException("edgeId", "the source must be a Java class with its file in the project");
        }
        String targetName = BlueprintJson.structure(target.getStructure()).className() != null
                ? BlueprintJson.structure(target.getStructure()).className() : target.getName().replaceAll("\\W", "");
        String targetFqn = target.getKey().startsWith("java:") ? target.getKey().substring("java:".length()) : null;
        Path folder = workspace.folderOf(projectId).orElseThrow(() ->
                new RequestValidationException("edgeId", "the project folder is not available"));
        Path file = WorkspacePaths.inside(folder, source.getFilePath());
        String before;
        try {
            before = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RequestValidationException("edgeId", "'" + source.getFilePath() + "' could not be read");
        }
        String after = withDependency(before, source.getName(), targetName, targetFqn);
        if (after.equals(before)) {
            throw new RequestValidationException("edgeId", source.getName() + " already has a " + targetName + " field");
        }
        String field = lowerCamel(targetName);
        return new ChangeProposal(projectId, source.getId(), edge.getId(), ProposalKind.ADD_DEPENDENCY, source.getFilePath(),
                BlueprintService.cut("Inietta " + targetName + " in " + source.getName(), 255),
                BlueprintService.cut(Optional.ofNullable(BlueprintService.blankToNull(draft.reason())).orElse(
                        "Nel Blueprint " + source.getName() + " " + BlueprintVocabulary.of(edge.getRelation()).label()
                                + " " + target.getName() + ", e il codice non ha questa dipendenza."), 2000),
                BlueprintService.cut(Optional.ofNullable(BlueprintService.blankToNull(draft.consequences())).orElse(
                        "Il costruttore di " + source.getName() + " riceve un parametro in più (" + targetName + " " + field
                                + "): chi lo costruisce a mano, test compresi, va aggiornato. Con l'iniezione di Spring "
                                + "non serve altro."), 2000),
                UnifiedDiff.between(source.getFilePath(), before, after), by);
    }

    /**
     * The source with the dependency added: an import when the target lives in
     * another package, a {@code private final} field, the constructor parameter
     * and its assignment. The AST says where; the edits are textual, in the file's
     * own indentation, so every line the change does not touch stays byte for byte.
     * With several constructors only the field is added: which constructor receives
     * it is a design decision, and the consequences say so.
     */
    static String withDependency(String source, String className, String targetName, String targetFqn) {
        CompilationUnit cu;
        try {
            cu = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                    .parse(source).getResult().orElseThrow();
        } catch (RuntimeException unparsable) {
            throw new RequestValidationException("edgeId", "the source file could not be parsed");
        }
        ClassOrInterfaceDeclaration type = cu.getClassByName(className)
                .or(() -> cu.findFirst(ClassOrInterfaceDeclaration.class, c -> !c.isInterface()))
                .orElseThrow(() -> new RequestValidationException("edgeId", "no class " + className + " in the file"));
        if (type.isInterface()) {
            throw new RequestValidationException("edgeId", "an interface cannot receive a dependency");
        }
        boolean present = type.getFields().stream().flatMap(f -> f.getVariables().stream())
                .anyMatch(v -> v.getType().asString().equals(targetName));
        if (present) {
            return source;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        List<TextEdit> edits = new ArrayList<>();
        String field = lowerCamel(targetName);

        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        if (targetFqn != null && targetFqn.contains(".") && !targetFqn.substring(0, targetFqn.lastIndexOf('.')).equals(pkg)
                && cu.getImports().stream().noneMatch(i -> i.getNameAsString().equals(targetFqn))) {
            if (cu.getImports().isNonEmpty()) {
                edits.add(TextEdit.lineAfter(endLine(cu.getImports().getLast().orElseThrow()), "import " + targetFqn + ";"));
            } else if (cu.getPackageDeclaration().isPresent()) {
                edits.add(TextEdit.lineAfter(endLine(cu.getPackageDeclaration().get()), "\nimport " + targetFqn + ";"));
            }
        }

        String memberIndent = type.getMembers().isEmpty() ? indentOf(lines, beginLine(type)) + "    "
                : indentOf(lines, beginLine(type.getMember(0)));
        String declaration = memberIndent + "private final " + targetName + " " + field + ";";
        boolean lombok = type.getAnnotationByName("RequiredArgsConstructor").isPresent()
                || type.getAnnotationByName("AllArgsConstructor").isPresent();
        List<ConstructorDeclaration> constructors = type.getConstructors();
        if (!lombok && constructors.isEmpty()) {
            declaration += "\n\n" + memberIndent + "public " + type.getNameAsString() + "(" + targetName + " " + field + ") {\n"
                    + memberIndent + "    this." + field + " = " + field + ";\n" + memberIndent + "}";
        }
        List<FieldDeclaration> fields = type.getFields();
        if (!fields.isEmpty()) {
            edits.add(TextEdit.lineAfter(endLine(fields.get(fields.size() - 1)), declaration));
        } else if (!type.getMembers().isEmpty()) {
            edits.add(TextEdit.lineBefore(beginLine(type.getMember(0)), declaration + "\n"));
        } else {
            edits.add(TextEdit.lineBefore(endLine(type), declaration));
        }

        if (!lombok && constructors.size() == 1) {
            ConstructorDeclaration c = constructors.get(0);
            String parameter = targetName + " " + field;
            if (c.getParameters().isNonEmpty()) {
                var end = c.getParameters().getLast().orElseThrow().getEnd().orElseThrow();
                edits.add(TextEdit.at(end.line, end.column, ", " + parameter));
            } else {
                var name = c.getName().getEnd().orElseThrow();
                int open = lines.get(name.line - 1).indexOf('(', name.column);
                if (open < 0) {
                    throw new RequestValidationException("edgeId", "the constructor of " + className + " could not be read");
                }
                edits.add(TextEdit.at(name.line, open + 1, parameter));
            }
            var body = c.getBody();
            int bodyBegin = body.getBegin().orElseThrow().line;
            int bodyEnd = body.getEnd().orElseThrow().line;
            String assignment = "this." + field + " = " + field + ";";
            if (bodyBegin == bodyEnd) {
                edits.add(TextEdit.at(bodyEnd, body.getEnd().orElseThrow().column - 1, " " + assignment + " "));
            } else {
                String indent = body.getStatements().isEmpty() ? indentOf(lines, beginLine(c)) + "    "
                        : indentOf(lines, beginLine(body.getStatement(0)));
                edits.add(TextEdit.lineBefore(bodyEnd, indent + assignment));
            }
        }

        // bottom-up, so every position read from the AST is still true when it is used
        edits.sort(java.util.Comparator.comparingInt(TextEdit::line).thenComparingInt(TextEdit::column).reversed());
        for (TextEdit e : edits) {
            e.applyTo(lines);
        }
        return String.join("\n", lines);
    }

    /** One textual change at an AST position: a new line after or before a line, or text at a column (1-based). */
    record TextEdit(int line, int column, String text, int mode) {
        static final int AFTER = 0;
        static final int BEFORE = 1;
        static final int INLINE = 2;

        static TextEdit lineAfter(int line, String text) {
            return new TextEdit(line, Integer.MAX_VALUE, text, AFTER);
        }

        static TextEdit lineBefore(int line, String text) {
            return new TextEdit(line, -1, text, BEFORE);
        }

        static TextEdit at(int line, int column, String text) {
            return new TextEdit(line, column, text, INLINE);
        }

        void applyTo(List<String> lines) {
            switch (mode) {
                case AFTER -> lines.addAll(line, Arrays.asList(text.split("\n", -1)));
                case BEFORE -> lines.addAll(line - 1, Arrays.asList(text.split("\n", -1)));
                default -> {
                    String l = lines.get(line - 1);
                    int at = Math.min(column, l.length());
                    lines.set(line - 1, l.substring(0, at) + text + l.substring(at));
                }
            }
        }
    }

    private static int beginLine(com.github.javaparser.ast.Node n) {
        return n.getComment().flatMap(com.github.javaparser.ast.Node::getBegin)
                .or(n::getBegin).orElseThrow().line;
    }

    private static int endLine(com.github.javaparser.ast.Node n) {
        return n.getEnd().orElseThrow().line;
    }

    private static String indentOf(List<String> lines, int line) {
        String l = lines.get(line - 1);
        return l.substring(0, l.length() - l.stripLeading().length());
    }

    private ChangeProposal custom(Long projectId, ProposalDraft draft, String by) {
        String path = BlueprintService.normalizePath(draft.filePath());
        if (path == null) {
            throw new RequestValidationException("filePath", "is required");
        }
        workspace.folderOf(projectId).ifPresent(folder -> WorkspacePaths.inside(folder, path));
        if (BlueprintService.blankToNull(draft.title()) == null) {
            throw new RequestValidationException("title", "is required");
        }
        if (BlueprintService.blankToNull(draft.diff()) == null) {
            throw new RequestValidationException("diff", "is required");
        }
        if (draft.diff().length() > 200_000) {
            throw new RequestValidationException("diff", "must be at most 200,000 characters");
        }
        if (draft.nodeId() != null) {
            blueprint.requireNode(projectId, draft.nodeId());
        }
        return new ChangeProposal(projectId, draft.nodeId(), null, ProposalKind.CUSTOM, path,
                BlueprintService.cut(draft.title().strip(), 255), BlueprintService.cut(BlueprintService.blankToNull(draft.reason()), 2000),
                BlueprintService.cut(BlueprintService.blankToNull(draft.consequences()), 2000),
                draft.diff().endsWith("\n") ? draft.diff() : draft.diff() + "\n", by);
    }

    // --- helpers -------------------------------------------------------------------

    private ChangeProposal decidable(Long projectId, Long proposalId, Precondition precondition) {
        ChangeProposal proposal = proposals.findByIdForUpdate(proposalId, projectId)
                .orElseThrow(() -> new ChangeProposalNotFoundException(projectId, proposalId));
        precondition.requireSatisfiedBy(proposal.getVersion());
        if (proposal.getStatus() != ProposalStatus.PROPOSED) {
            throw new ProposalDecidedException(proposalId, proposal.getStatus().name());
        }
        return proposal;
    }

    /** The first version of a file the Blueprint designed: what it is for, and its pseudocode as the plan. */
    static String skeleton(BlueprintNode node) {
        String path = node.getFilePath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String base = fileName.contains(".") ? fileName.substring(0, fileName.indexOf('.')) : fileName;
        String description = Optional.ofNullable(node.getDescription()).orElse(node.getName());
        List<String> plan = Optional.ofNullable(node.getPseudocode()).map(p -> p.lines().toList()).orElse(List.of());
        String lower = path.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        if (lower.endsWith(".java")) {
            int src = path.indexOf("src/main/java/");
            int test = path.indexOf("src/test/java/");
            int root = src >= 0 ? src + "src/main/java/".length() : test >= 0 ? test + "src/test/java/".length() : -1;
            if (root >= 0 && path.lastIndexOf('/') > root) {
                out.append("package ").append(path.substring(root, path.lastIndexOf('/')).replace('/', '.')).append(";\n\n");
            }
            String annotation = switch (node.getKind()) {
                case SERVICE -> "@org.springframework.stereotype.Service\n";
                case CONTROLLER -> "@org.springframework.web.bind.annotation.RestController\n";
                case CONFIGURATION -> "@org.springframework.context.annotation.Configuration\n";
                case ENTITY -> "@jakarta.persistence.Entity\n";
                default -> "";
            };
            out.append("/**\n * ").append(description).append("\n");
            if (!plan.isEmpty()) {
                out.append(" *\n * <pre>\n");
                plan.forEach(l -> out.append(" * ").append(l).append('\n'));
                out.append(" * </pre>\n");
            }
            out.append(" */\n").append(annotation);
            String declaration = switch (node.getKind()) {
                case INTERFACE, REPOSITORY -> "public interface ";
                case ENUM -> "public enum ";
                case DTO -> "public record ";
                default -> "public class ";
            };
            out.append(declaration).append(base).append(node.getKind() == NodeKind.DTO ? "() {\n}\n" : " {\n}\n");
        } else if (lower.matches(".*\\.[jt]sx?$")) {
            out.append("// ").append(description).append('\n');
            plan.forEach(l -> out.append("// ").append(l).append('\n'));
            if (node.getKind() == NodeKind.COMPONENT) {
                out.append("\nexport function ").append(base).append("() {\n  return <div>").append(base).append("</div>;\n}\n");
            } else if (node.getKind() == NodeKind.HOOK) {
                out.append("\nexport function ").append(base).append("() {\n  // TODO\n}\n");
            } else {
                out.append("\nexport {};\n");
            }
        } else if (lower.endsWith(".py")) {
            out.append("\"\"\"").append(description).append("\"\"\"\n");
            plan.forEach(l -> out.append("# ").append(l).append('\n'));
        } else if (lower.endsWith(".md")) {
            out.append("# ").append(node.getName()).append("\n\n").append(description).append('\n');
        } else {
            out.append(description).append('\n');
        }
        CodeStructure s = BlueprintJson.structure(node.getStructure());
        if (!s.methods().isEmpty() && lower.endsWith(".java")) {
            // the methods the operator designed, as stubs to fill
            int close = out.lastIndexOf("}");
            StringBuilder stubs = new StringBuilder();
            for (CodeStructure.CodeMethod m : s.methods()) {
                stubs.append("\n    ").append(Optional.ofNullable(m.visibility()).orElse("public")).append(' ')
                        .append(Optional.ofNullable(m.returnType()).orElse("void")).append(' ').append(m.name())
                        .append('(').append(String.join(", ", m.parameters())).append(") {\n        throw new UnsupportedOperationException(\"TODO\");\n    }\n");
            }
            out.insert(close, stubs);
        }
        return out.toString();
    }

    static String lowerCamel(String name) {
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    static BlueprintViews.BlueprintProposal view(ChangeProposal p) {
        return new BlueprintViews.BlueprintProposal(p.getId(), p.getNodeId(), p.getEdgeId(), p.getKind(), p.getStatus(), p.getFilePath(),
                p.getTitle(), p.getReason(), p.getConsequences(), p.getDiff(), p.getTaskId(), p.getProposedBy(),
                p.getDecidedBy(), p.getDecidedAt(), p.getCreatedAt(), p.getVersion());
    }
}
