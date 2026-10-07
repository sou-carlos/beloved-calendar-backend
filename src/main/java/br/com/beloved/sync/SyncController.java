package br.com.beloved.sync;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public SyncController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Gift(
        @NotBlank @Size(max=100) String id,
        @NotBlank @Size(max=200) String title,
        @Size(max=4000) String notes,
        @Size(max=2048) @Pattern(regexp="(?i)^(https?://.*)?$") String url,
        Boolean purchased) {}

    /** Avatar option ids. Any well-formed id is kept, so faces from newer app versions survive older ones. */
    private static final String OPTION_ID = "[a-z0-9-]+";

    public record Avatar(
        @NotBlank @Size(max=32) @Pattern(regexp=OPTION_ID) String hair,
        @NotBlank @Size(max=32) @Pattern(regexp=OPTION_ID) String hairColor,
        @NotBlank @Size(max=32) @Pattern(regexp=OPTION_ID) String skin,
        @NotBlank @Size(max=32) @Pattern(regexp=OPTION_ID) String eyes,
        // Optional choices added after the first faces were saved.
        @Size(max=32) @Pattern(regexp=OPTION_ID) String shirt,
        @Size(max=32) @Pattern(regexp=OPTION_ID) String age,
        @Size(max=32) @Pattern(regexp=OPTION_ID) String hat,
        @Size(max=32) @Pattern(regexp=OPTION_ID) String earrings,
        @Size(max=32) @Pattern(regexp=OPTION_ID) String glasses,
        @Size(max=32) @Pattern(regexp=OPTION_ID) String beard) {}

    public record Person(
        @NotBlank @Size(max=100) String id,
        @NotBlank @Size(max=80) String name,
        @NotBlank @Pattern(regexp="[0-9]{4}-[0-9]{2}-[0-9]{2}") String birthDate,
        @Size(max=4000) String notes,
        @Size(max=2000000) String image,
        @NotNull @Size(max=500) List<@NotNull @Valid Gift> gifts,
        @Size(max=2000) String likes,
        @Size(max=2000) String dislikes,
        @Size(max=32) String emoji,
        Boolean yearUnknown,
        @Valid Avatar avatar) {
        /** The unknown-year flag only applies to the placeholder year 2000. Older clients can leave it stale after a date edit. */
        Person normalized() {
            if (!Boolean.TRUE.equals(yearUnknown) || birthDate.startsWith("2000-")) return this;
            return new Person(id, name, birthDate, notes, image, gifts, likes, dislikes, emoji, null, avatar);
        }
    }

    public record Change(@NotNull UUID accountId, @NotNull UUID operationId,
        @NotBlank @Size(max=100) String id, @PositiveOrZero long baseVersion,
        @Valid Person person) {}
    public record Entry(String id, long version, Person person) {}
    public record Result(UUID accountId, UUID operationId, String status, Entry record) {}
    public record Snapshot(UUID accountId, List<Entry> records) {}

    private UUID owner(Authentication authentication, UUID expected) {
        UUID actual = jdbc.queryForObject("SELECT id FROM app_users WHERE email = ?", UUID.class, authentication.getName());
        if (!Objects.equals(actual, expected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A conta mudou. Entre novamente antes de sincronizar.");
        }
        return actual;
    }

    @GetMapping
    @Transactional(readOnly=true)
    public Snapshot snapshot(Authentication authentication, @RequestParam UUID accountId) {
        UUID user = owner(authentication, accountId);
        return new Snapshot(user, jdbc.query("SELECT record_id, version, document::text FROM calendar_records WHERE user_id = ? ORDER BY record_id",
            (rs, row) -> new Entry(rs.getString(1), rs.getLong(2), decode(rs.getString(3))), user));
    }

    @PostMapping
    @Transactional
    public Result change(Authentication authentication, @Valid @RequestBody Change body) {
        UUID user = owner(authentication, body.accountId());
        validatePerson(body);
        // Serialize mutations per account, including concurrent creation of a missing record.
        jdbc.queryForObject("SELECT id FROM app_users WHERE id = ? FOR UPDATE", UUID.class, user);
        String request = json.writeValueAsString(body);
        var previous = jdbc.query("SELECT request::text, response::text FROM calendar_operations WHERE user_id = ? AND operation_id = ?",
            (rs, row) -> new String[]{rs.getString(1), rs.getString(2)}, user, body.operationId());
        if (!previous.isEmpty()) {
            if (!json.readTree(previous.getFirst()[0]).equals(json.readTree(request))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Operação já utilizada com outros dados.");
            }
            return json.readValue(previous.getFirst()[1], Result.class);
        }
        var entries = jdbc.query("SELECT record_id, version, document::text FROM calendar_records WHERE user_id = ? AND record_id = ?",
            (rs, row) -> new Entry(rs.getString(1), rs.getLong(2), decode(rs.getString(3))), user, body.id());
        Entry current = entries.isEmpty() ? new Entry(body.id(), 0, null) : entries.getFirst();
        Result result;
        if (current.version() != body.baseVersion()) {
            result = new Result(user, body.operationId(), "conflict", current);
        } else {
            Entry saved = new Entry(body.id(), current.version() + 1, body.person() == null ? null : body.person().normalized());
            jdbc.update("INSERT INTO calendar_records (user_id, record_id, version, document) VALUES (?, ?, ?, CAST(? AS jsonb)) "
                + "ON CONFLICT (user_id, record_id) DO UPDATE SET version = EXCLUDED.version, document = EXCLUDED.document, updated_at = now()",
                user, saved.id(), saved.version(), saved.person() == null ? null : json.writeValueAsString(saved.person()));
            result = new Result(user, body.operationId(), "accepted", saved);
        }
        // Persist both accepted and conflicting results so a lost response can be retried safely.
        jdbc.update("INSERT INTO calendar_operations (user_id, operation_id, request, response) VALUES (?, ?, CAST(? AS jsonb), CAST(? AS jsonb))",
            user, body.operationId(), request, json.writeValueAsString(result));
        return result;
    }

    private Person decode(String document) { return document == null ? null : json.readValue(document, Person.class); }

    private void validatePerson(Change body) {
        Person person = body.person();
        if (person == null) return;
        if (!body.id().equals(person.id())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador do amigo inválido.");
        try {
            if (LocalDate.parse(person.birthDate()).getYear() < 1) throw new IllegalArgumentException();
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aniversário inválido.");
        }
        if (person.gifts().stream().map(Gift::id).distinct().count() != person.gifts().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Presentes com identificadores repetidos.");
        }
    }
}
