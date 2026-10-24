package ru.akiev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Логическая модель, которую обслуживают несколько провайдеров, у каждого свой id.
 *
 * <p>Клиент шлёт {@code name} или любой из {@code aliases} (например {@code gpt-oss-120b}
 * и {@code openai/gpt-oss-120b}). Шлюз выбирает участника и подменяет model на его id.
 *
 * @param name    имя, которое шлёт клиент
 * @param aliases дополнительные имена той же группы (провайдерские id, старые имена клиентов)
 * @param members провайдер и model id, который ему реально уйдёт
 */
public record ModelGroupProperties(
        @NotBlank String name,
        List<String> aliases,
        @NotEmpty @Valid List<Member> members) {

    @ConstructorBinding
    public ModelGroupProperties {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** Один провайдер в группе и модель, которую нужно отправить именно ему. */
    public record Member(@NotBlank String provider, @NotBlank String model) {

        @ConstructorBinding
        public Member {
        }
    }
}
