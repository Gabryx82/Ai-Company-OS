package com.aicompany.backend.harness.hub;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a skill is for, at a glance (ADR-036 §4): one functional category per
 * skill, from the words of its name, its plugin and its description. The rules
 * are ordered -- the first one that matches wins -- and the name and plugin
 * weigh more than the description, which mentions everything.
 */
public final class SkillCategories {

    public record Category(String key, String label) {
    }

    private record Rule(Category category, Pattern words) {
    }

    public static final Category OTHER = new Category("altro", "Altro");

    private static final List<Rule> RULES = List.of(
            rule("documenti", "Documenti e Office",
                    "pdf|docx?|word|xlsx|excel|pptx|powerpoint|presentation|presentations|slides?|spreadsheets?|documents?|"
                            + "doc-coauthoring|docs|markdown|template-creator|google-workspace|sharepoint"),
            rule("giochi-3d", "Giochi e 3D",
                    "unity|unreal|game|games|gaming|3d|blender|shader|sprite|sprites|tilemap|pixel|navmesh|levelplay|godot"),
            rule("design", "Design e UI",
                    "design|figma|ui|ux|css|tailwind|mockup|mockups|accessibility|brand|canva|frontend-design|"
                            + "generative_ui|visualize|image|imagegen|create-image|icons?|motion"),
            rule("sicurezza", "Sicurezza",
                    "security|secure|securecoder|threat|vulnerabilit(y|ies)|audit|scan|scanner|poc|sast|secrets?|cve"),
            rule("devops", "DevOps e cloud",
                    "deploy|deployment|docker|kubernetes|k8s|render|vercel|netlify|cloudflare|aws|azure|gcp|terraform|"
                            + "ci|cd|cron|postgres|database|supabase|firebase|redis|keyvalue|hosting|domains?|scaling|"
                            + "workers?|infrastructure|devops|heroku"),
            rule("business", "Business, vendite e marketing",
                    "sales|marketing|crm|finance|financial|invoice|payroll|tax|lead|leads|pipeline|campaign|seo|customer|"
                            + "deal|deals|forecast|accounting|journal|reconciliation|budget|pricing|shopify|ecommerce|"
                            + "small-business|business|grant|proposal|outreach|hiring|competitive|stakeholder|roadmap|"
                            + "product-management|sprint|spec"),
            rule("dati", "Dati e analisi",
                    "data|analytics|metrics|chart|charts|sql|dashboard|report|reports|bigquery|statistics|research|"
                            + "dataviz|visualization|synthesis"),
            rule("produttivita", "Produttività e comunicazione",
                    "task|tasks|calendar|email|gmail|outlook|inbox|memory|schedule|notes|meeting|meetings|slack|teams|"
                            + "morning|standup|productivity|clickup|asana|notion|linear|todo|brief|update"),
            rule("browser", "Browser e automazione",
                    "browser|chrome|computer-use|automation|playwright|puppeteer|scrap(e|ing)|web|navigation"),
            rule("ai-agenti", "AI, agenti e skill",
                    "agent|agents|skill|skills|prompt|prompts|mcp|claude|codex|gemini|antigravity|llm|model|models|"
                            + "plugin|plugins|openai|anthropic|customizations|hooks|subagent|learn|explain"),
            rule("sviluppo", "Sviluppo software",
                    "code|coding|react|java|kotlin|python|typescript|javascript|node|api|apis|rest|test|tests|testing|"
                            + "debug|debugging|refactor|frontend|backend|lsp|git|github|gitlab|review|sdk|cli|spring|"
                            + "dotnet|aspnet|swift|ios|android|expo|build|architecture|tdd|lint|migration|migrate|"
                            + "documentation|engineering|programming|developer|developers|app|apps"));

    private SkillCategories() {
    }

    /** The category of a skill; {@link #OTHER} when nothing matches. */
    public static Category of(String name, String plugin, String description) {
        String strong = words(name) + " " + words(plugin);
        for (Rule rule : RULES) {
            if (rule.words().matcher(strong).find()) {
                return rule.category();
            }
        }
        String weak = words(description);
        for (Rule rule : RULES) {
            if (rule.words().matcher(weak).find()) {
                return rule.category();
            }
        }
        return OTHER;
    }

    public static List<Category> all() {
        return java.util.stream.Stream.concat(RULES.stream().map(Rule::category), java.util.stream.Stream.of(OTHER)).toList();
    }

    private static Rule rule(String key, String label, String words) {
        return new Rule(new Category(key, label), Pattern.compile("(^|[^a-z0-9])(" + words + ")([^a-z0-9]|$)"));
    }

    private static String words(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
