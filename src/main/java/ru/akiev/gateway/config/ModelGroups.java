package ru.akiev.gateway.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.akiev.gateway.config.ModelGroupProperties.Member;

/**
 * Скомпилированные группы моделей: алиас → группа, и какие model id провайдер должен принимать.
 *
 * <p>{@code auto} (если так не названа явная группа) — любой включённый провайдер со своей моделью.
 */
public final class ModelGroups {

    public static final String AUTO = "auto";
    public static final ModelGroups EMPTY = new ModelGroups(Map.of(), List.of(), Map.of());

    private static final Logger log = LoggerFactory.getLogger(ModelGroups.class);

    public enum Kind {
        /** Как раньше: точное совпадение id, иначе все провайдеры. */
        EXACT,
        /** Участники группы, model переписывается. 404/401/403/402 — failover внутри группы. */
        GROUP,
        /** Любой включённый провайдер, каждому уходит его собственная модель. */
        AUTO
    }

    public record Resolution(Kind kind, ModelGroupProperties group) {
        public boolean spreadsFailover() {
            return kind == Kind.GROUP || kind == Kind.AUTO;
        }
    }

    private final Map<String, ModelGroupProperties> byAlias;
    private final List<String> advertised;
    private final Map<String, List<String>> extraModelsByProvider;

    private ModelGroups(Map<String, ModelGroupProperties> byAlias, List<String> advertised,
            Map<String, List<String>> extraModelsByProvider) {
        this.byAlias = byAlias;
        this.advertised = advertised;
        this.extraModelsByProvider = extraModelsByProvider;
    }

    /**
     * Собрать группы. Алиас в двух группах — ошибка старта. Участник, которого нет среди включённых
     * провайдеров, пропускается с предупреждением (группа без участников остаётся: запрос к ней — 503,
     * а не «все провайдеры подряд»).
     */
    public static ModelGroups compile(List<ModelGroupProperties> configured, Collection<String> enabledNames) {
        if (configured == null || configured.isEmpty()) {
            return EMPTY;
        }
        Set<String> enabled = Set.copyOf(enabledNames);
        Map<String, ModelGroupProperties> byAlias = new LinkedHashMap<>();
        Map<String, String> owner = new LinkedHashMap<>();
        List<String> advertised = new ArrayList<>();
        Map<String, LinkedHashSet<String>> extra = new LinkedHashMap<>();
        for (ModelGroupProperties group : configured) {
            claim(owner, group.name(), group.name());
            for (String alias : group.aliases()) {
                if (alias == null || alias.isBlank() || alias.equals(group.name())) {
                    continue;
                }
                claim(owner, alias, group.name());
            }
            List<Member> live = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Member member : group.members()) {
                if (!enabled.contains(member.provider())) {
                    log.warn("Группа {}: провайдер {} не включён — участник пропущен", group.name(), member.provider());
                    continue;
                }
                if (!seen.add(member.provider())) {
                    log.warn("Группа {}: провайдер {} указан дважды — берём модель {}", group.name(),
                            member.provider(), member.model());
                    live.removeIf(m -> m.provider().equals(member.provider()));
                }
                live.add(member);
                extra.computeIfAbsent(member.provider(), k -> new LinkedHashSet<>()).add(member.model());
            }
            if (live.isEmpty()) {
                log.warn("Группа {}: ни одного включённого провайдера — запросы к этой модели получат 503",
                        group.name());
            }
            ModelGroupProperties compiled = new ModelGroupProperties(group.name(), group.aliases(), live);
            byAlias.put(group.name(), compiled);
            advertised.add(group.name());
            for (String alias : group.aliases()) {
                if (alias == null || alias.isBlank() || alias.equals(group.name())) {
                    continue;
                }
                byAlias.put(alias, compiled);
                advertised.add(alias);
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>();
        extra.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return new ModelGroups(Map.copyOf(byAlias), List.copyOf(advertised), Map.copyOf(frozen));
    }

    private static void claim(Map<String, String> owner, String alias, String group) {
        String prev = owner.put(alias, group);
        if (prev != null && !prev.equals(group)) {
            throw new IllegalStateException("Имя модели '" + alias + "' указано и в группе '" + prev
                    + "', и в группе '" + group + "'");
        }
    }

    public Resolution resolve(String model) {
        if (model == null || model.isBlank()) {
            return new Resolution(Kind.EXACT, null);
        }
        String key = model.trim();
        ModelGroupProperties group = byAlias.get(key);
        if (group != null) {
            return new Resolution(Kind.GROUP, group);
        }
        if (AUTO.equals(key)) {
            return new Resolution(Kind.AUTO, null);
        }
        return new Resolution(Kind.EXACT, null);
    }

    /** Model id, который уходит этому провайдеру, или null если его нет в группе. */
    public String modelFor(ModelGroupProperties group, String provider) {
        if (group == null) {
            return null;
        }
        String found = null;
        for (Member member : group.members()) {
            if (member.provider().equals(provider)) {
                found = member.model();
            }
        }
        return found;
    }

    public Set<String> providerNames(ModelGroupProperties group) {
        if (group == null) {
            return Set.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Member member : group.members()) {
            names.add(member.provider());
        }
        return Set.copyOf(names);
    }

    /** Имена и алиасы групп — их клиент видит в /v1/models, если провайдер сам такое id не отдаёт. */
    public List<String> advertised() {
        return advertised;
    }

    public List<String> extraModels(String provider) {
        return extraModelsByProvider.getOrDefault(provider, List.of());
    }
}
