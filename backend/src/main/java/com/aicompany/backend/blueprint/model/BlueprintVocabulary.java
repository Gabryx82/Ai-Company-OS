package com.aicompany.backend.blueprint.model;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the Blueprint's words mean (ADR-033 §2): the label, family, layer and
 * layout column of every kind, the family and label of every relation, and the
 * AI actions. The console receives all of it from {@code GET /api/blueprint/vocabulary}
 * so no classification lives in the frontend -- it only picks colours and icons.
 */
public final class BlueprintVocabulary {

    /** The architectural layer a block belongs to: the first row of filters. */
    public enum Layer { BACKEND, FRONTEND, DATABASE, TESTS, DOCS, OTHER }

    /** The kind of thing a block is: the second row of filters. */
    public enum Family { FILES, CLASSES, METHODS, APIS, DATA, TESTS, CUSTOM }

    /** Which structured section the Inspector shows for a kind. */
    public enum Section { CLASS, FUNCTION, ENDPOINT, FRONTEND, DATABASE, NONE }

    /** How the canvas draws a relation: its family decides the colour. */
    public enum RelationFamily { DEPENDENCY, STRUCTURE, DATA, EVENTS, TESTS, CUSTOM }

    public record BlueprintKindInfo(NodeKind kind, String label, Family family, Layer defaultLayer, Section section, int column) {
    }

    public record BlueprintRelationInfo(RelationType relation, String label, RelationFamily family, String description) {
    }

    public record BlueprintActionInfo(AiAction action, String label, String description) {
    }

    private static final Map<NodeKind, BlueprintKindInfo> KINDS = new EnumMap<>(NodeKind.class);
    private static final Map<RelationType, BlueprintRelationInfo> RELATIONS = new EnumMap<>(RelationType.class);
    private static final Map<AiAction, BlueprintActionInfo> ACTIONS = new EnumMap<>(AiAction.class);

    private static void kind(NodeKind k, String label, Family f, Layer l, Section s, int column) {
        KINDS.put(k, new BlueprintKindInfo(k, label, f, l, s, column));
    }

    private static void relation(RelationType r, String label, RelationFamily f, String description) {
        RELATIONS.put(r, new BlueprintRelationInfo(r, label, f, description));
    }

    private static void action(AiAction a, String label, String description) {
        ACTIONS.put(a, new BlueprintActionInfo(a, label, description));
    }

    static {
        // Columns run left to right in the direction of the dependencies (ADR-033 §6):
        // tests -> frontend -> endpoints -> controllers -> services -> repositories
        // and support classes -> entities and DTOs -> database -> configuration and docs.
        kind(NodeKind.TEST, "Test", Family.TESTS, Layer.TESTS, Section.CLASS, 0);
        kind(NodeKind.COMPONENT, "Componente frontend", Family.CLASSES, Layer.FRONTEND, Section.FRONTEND, 1);
        kind(NodeKind.HOOK, "Hook", Family.CLASSES, Layer.FRONTEND, Section.FUNCTION, 2);
        kind(NodeKind.STORE, "Store", Family.CLASSES, Layer.FRONTEND, Section.FRONTEND, 2);
        kind(NodeKind.ENDPOINT, "Endpoint API", Family.APIS, Layer.BACKEND, Section.ENDPOINT, 3);
        kind(NodeKind.CONTROLLER, "Controller", Family.APIS, Layer.BACKEND, Section.CLASS, 4);
        kind(NodeKind.SERVICE, "Service", Family.CLASSES, Layer.BACKEND, Section.CLASS, 5);
        kind(NodeKind.REPOSITORY, "Repository", Family.DATA, Layer.DATABASE, Section.CLASS, 6);
        kind(NodeKind.CLASS, "Classe", Family.CLASSES, Layer.BACKEND, Section.CLASS, 6);
        kind(NodeKind.INTERFACE, "Interfaccia", Family.CLASSES, Layer.BACKEND, Section.CLASS, 6);
        kind(NodeKind.FUNCTION, "Metodo / funzione", Family.METHODS, Layer.BACKEND, Section.FUNCTION, 6);
        kind(NodeKind.SCRIPT, "Script", Family.FILES, Layer.OTHER, Section.FUNCTION, 6);
        kind(NodeKind.FILE, "File", Family.FILES, Layer.BACKEND, Section.NONE, 6);
        kind(NodeKind.ENUM, "Enum", Family.CLASSES, Layer.BACKEND, Section.CLASS, 7);
        kind(NodeKind.DTO, "DTO", Family.DATA, Layer.BACKEND, Section.CLASS, 7);
        kind(NodeKind.ENTITY, "Entità / model", Family.DATA, Layer.DATABASE, Section.CLASS, 7);
        kind(NodeKind.DATABASE, "Database / migrazione", Family.DATA, Layer.DATABASE, Section.DATABASE, 8);
        kind(NodeKind.CONFIGURATION, "Configurazione", Family.FILES, Layer.BACKEND, Section.CLASS, 9);
        kind(NodeKind.DOCUMENT, "Documentazione", Family.FILES, Layer.DOCS, Section.NONE, 9);
        kind(NodeKind.SNIPPET, "Snippet di codice", Family.CUSTOM, Layer.OTHER, Section.FUNCTION, 9);
        kind(NodeKind.CUSTOM, "Componente custom", Family.CUSTOM, Layer.OTHER, Section.NONE, 9);

        relation(RelationType.IMPORTS, "importa", RelationFamily.DEPENDENCY, "Il sorgente importa il destinatario");
        relation(RelationType.USES, "usa", RelationFamily.DEPENDENCY,
                "Il sorgente dipende dal destinatario (campo, iniezione, parametro)");
        relation(RelationType.CALLS, "chiama", RelationFamily.DEPENDENCY, "Il sorgente invoca metodi del destinatario");
        relation(RelationType.EXTENDS, "estende", RelationFamily.STRUCTURE, "Ereditarietà");
        relation(RelationType.IMPLEMENTS, "implementa", RelationFamily.STRUCTURE, "Il sorgente implementa l'interfaccia");
        relation(RelationType.READS, "legge", RelationFamily.DATA, "Il sorgente legge dati dal destinatario");
        relation(RelationType.WRITES, "scrive", RelationFamily.DATA, "Il sorgente scrive dati nel destinatario");
        relation(RelationType.CREATES, "crea", RelationFamily.DATA, "Il sorgente crea istanze del destinatario");
        relation(RelationType.CONTAINS, "contiene", RelationFamily.STRUCTURE, "Il destinatario è una parte del sorgente");
        relation(RelationType.BELONGS_TO, "appartiene a", RelationFamily.STRUCTURE, "Il sorgente fa parte del destinatario");
        relation(RelationType.VALIDATES, "valida", RelationFamily.DATA, "Il sorgente verifica il destinatario");
        relation(RelationType.EMITS, "emette", RelationFamily.EVENTS, "Il sorgente pubblica un evento");
        relation(RelationType.SUBSCRIBES, "ascolta", RelationFamily.EVENTS, "Il sorgente reagisce a un evento");
        relation(RelationType.MAPS_TO, "mappa su", RelationFamily.DATA, "Conversione fra rappresentazioni (DTO, entità)");
        relation(RelationType.ENDPOINT_TO_SERVICE, "endpoint → service", RelationFamily.DEPENDENCY,
                "L'endpoint delega la logica al service");
        relation(RelationType.SERVICE_TO_REPOSITORY, "service → repository", RelationFamily.DEPENDENCY,
                "Il service accede ai dati attraverso il repository");
        relation(RelationType.ENTITY_TO_TABLE, "entità → tabella", RelationFamily.DATA,
                "L'entità è mappata sulla tabella");
        relation(RelationType.TEST_COVERS, "testa", RelationFamily.TESTS, "Il test esercita il destinatario");
        relation(RelationType.CUSTOM, "personalizzata", RelationFamily.CUSTOM, "Relazione definita dall'operatore");

        action(AiAction.EXPLAIN_COMPONENT, "Spiega il componente", "Che cosa fa, perché esiste, come si usa");
        action(AiAction.GENERATE_PSEUDOCODE, "Genera pseudocodice", "Pseudocodice leggibile dal codice reale");
        action(AiAction.DETECT_MISSING_RELATIONS, "Trova relazioni mancanti",
                "Dipendenze che il codice ha e il grafo non mostra");
        action(AiAction.EXPLAIN_DEPENDENCY, "Spiega le dipendenze", "Perché il componente dipende da ciò che usa");
        action(AiAction.SUGGEST_REFACTOR, "Suggerisci un refactor", "Proposte motivate, senza modificare il codice");
        action(AiAction.FIND_ARCHITECTURAL_ISSUE, "Cerca problemi architetturali",
                "Accoppiamenti, cicli, responsabilità confuse");
        action(AiAction.GENERATE_DOCUMENTATION, "Genera documentazione", "Documentazione del componente in Markdown");
        action(AiAction.CREATE_IMPLEMENTATION_TASK, "Crea task di implementazione",
                "Una task per realizzare o completare il componente");
    }

    private BlueprintVocabulary() {
    }

    public static BlueprintKindInfo of(NodeKind kind) {
        return KINDS.get(kind);
    }

    public static BlueprintRelationInfo of(RelationType relation) {
        return RELATIONS.get(relation);
    }

    public static BlueprintActionInfo of(AiAction action) {
        return ACTIONS.get(action);
    }

    public static List<BlueprintKindInfo> kinds() {
        return Arrays.stream(NodeKind.values()).map(KINDS::get).toList();
    }

    public static List<BlueprintRelationInfo> relations() {
        return Arrays.stream(RelationType.values()).map(RELATIONS::get).toList();
    }

    public static List<BlueprintActionInfo> actions() {
        return Arrays.stream(AiAction.values()).map(ACTIONS::get).toList();
    }

    /**
     * The layer of a block: the kind's own, unless the language and path say
     * otherwise -- a TypeScript file under {@code frontend/} is frontend whatever
     * its kind, a test is a test wherever it lives.
     */
    public static Layer layerOf(NodeKind kind, String language, String path) {
        BlueprintKindInfo info = KINDS.get(kind);
        if (kind == NodeKind.TEST || kind == NodeKind.DOCUMENT || kind == NodeKind.DATABASE
                || kind == NodeKind.ENTITY || kind == NodeKind.REPOSITORY) {
            return info.defaultLayer();
        }
        String p = path == null ? "" : "/" + path.toLowerCase(Locale.ROOT).replace('\\', '/');
        if (p.endsWith(".md") || p.contains("/docs/")) {
            return Layer.DOCS;
        }
        if (p.contains("/test/") || p.contains("/tests/") || p.matches(".*\\.(test|spec)\\.[jt]sx?$")) {
            return Layer.TESTS;
        }
        boolean script = "typescript".equals(language) || "javascript".equals(language);
        if (info.defaultLayer() == Layer.FRONTEND
                || (script && !p.contains("/server/") && !p.contains("/backend/") && !p.contains("/api/"))
                || p.contains("/frontend/") || p.contains("/web/") || p.contains("/client/")) {
            return Layer.FRONTEND;
        }
        return info.defaultLayer();
    }

    /** The layout column of a block: frontend blocks gather on the left, whatever their kind. */
    public static int columnOf(NodeKind kind, Layer layer) {
        int column = KINDS.get(kind).column();
        if (layer == Layer.FRONTEND && column > 2) {
            return kind == NodeKind.COMPONENT ? 1 : 2;
        }
        if (layer == Layer.TESTS) {
            return 0;
        }
        return column;
    }
}
